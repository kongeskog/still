package com.kongeskog.still.bridge

import com.kongeskog.still.bridge.db.ACCOUNT_ACI
import com.kongeskog.still.bridge.db.IdentityRow
import com.kongeskog.still.bridge.db.KyberPreKeyRow
import com.kongeskog.still.bridge.db.KyberUsedRow
import com.kongeskog.still.bridge.db.PreKeyRow
import com.kongeskog.still.bridge.db.SenderKeyRow
import com.kongeskog.still.bridge.db.SenderKeySharedRow
import com.kongeskog.still.bridge.db.SessionRow
import com.kongeskog.still.bridge.db.SignedPreKeyRow
import com.kongeskog.still.bridge.db.StillDatabase
import org.signal.core.models.ServiceId
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.NoSessionException
import org.signal.libsignal.protocol.ReusedBaseKeyException
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.whispersystems.signalservice.api.SignalServiceAccountDataStore
import org.whispersystems.signalservice.api.SignalServiceDataStore
import org.whispersystems.signalservice.api.SignalSessionLock
import org.whispersystems.signalservice.api.push.DistributionId
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock

/** One lock around all session reads and writes, as the library expects. */
internal class SessionLock : SignalSessionLock {
    private val lock = ReentrantLock()
    override fun acquire(): SignalSessionLock.Lock {
        lock.lock()
        return SignalSessionLock.Lock { lock.unlock() }
    }
}

internal class DataStore(db: StillDatabase, private val account: AccountStore) : SignalServiceDataStore {
    private val aci = ProtocolStore(ACCOUNT_ACI, db, account)
    private val pni = ProtocolStore(1, db, account)

    override fun get(serviceId: ServiceId): SignalServiceAccountDataStore =
        if (serviceId.toString() == account.pni) pni else aci
    override fun aci(): SignalServiceAccountDataStore = aci
    override fun pni(): SignalServiceAccountDataStore = pni
    override fun pniOrNull(): SignalServiceAccountDataStore? = if (account.pni != null) pni else null
    override fun isMultiDevice(): Boolean = true
}

/**
 * libsignal's key and session storage for one identity (ACI or PNI), on Room.
 * Sender keys are shared between the two, as in Signal.
 */
internal class ProtocolStore(
    private val account: Int,
    db: StillDatabase,
    private val accountStore: AccountStore,
) : SignalServiceAccountDataStore {
    private val keys = db.preKeys()
    private val dao = db.sessions()

    private val identity: IdentityKeyPair by lazy { accountStore.identity(account) ?: error("No identity key for account $account") }
    private val registrationId: Int by lazy {
        accountStore.int(if (account == ACCOUNT_ACI) AccountStore.ACI_REGISTRATION_ID else AccountStore.PNI_REGISTRATION_ID) ?: 0
    }

    // Identity keys. Trust on first use; a changed key replaces the old one on receipt.
    override fun getIdentityKeyPair(): IdentityKeyPair = identity
    override fun getLocalRegistrationId(): Int = registrationId

    override fun saveIdentity(address: SignalProtocolAddress, identityKey: IdentityKey): IdentityKeyStore.IdentityChange {
        val existing = dao.identity(account, address.name)
        val bytes = identityKey.serialize()
        if (existing != null && existing.identityKey.contentEquals(bytes)) return IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
        dao.putIdentity(IdentityRow(account, address.name, bytes, System.currentTimeMillis(), 0))
        return if (existing == null) IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED else IdentityKeyStore.IdentityChange.REPLACED_EXISTING
    }

    override fun isTrustedIdentity(address: SignalProtocolAddress, identityKey: IdentityKey, direction: IdentityKeyStore.Direction): Boolean {
        if (direction == IdentityKeyStore.Direction.RECEIVING) return true
        val existing = dao.identity(account, address.name) ?: return true
        return existing.identityKey.contentEquals(identityKey.serialize())
    }

    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? =
        dao.identity(account, address.name)?.let { IdentityKey(it.identityKey) }

    // Sessions
    override fun loadSession(address: SignalProtocolAddress): SessionRecord =
        dao.session(account, address.name, address.deviceId)?.let { SessionRecord(it.record) } ?: SessionRecord()

    override fun loadExistingSessions(addresses: List<SignalProtocolAddress>): List<SessionRecord> = addresses.map {
        dao.session(account, it.name, it.deviceId)?.let { row -> SessionRecord(row.record) } ?: throw NoSessionException(it, "No session for $it")
    }

    override fun getSubDeviceSessions(name: String): List<Int> = dao.sessionDevices(account, name).filter { it != 1 }

    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) =
        dao.putSession(SessionRow(account, address.name, address.deviceId, record.serialize()))

    override fun containsSession(address: SignalProtocolAddress): Boolean =
        dao.session(account, address.name, address.deviceId)?.let { SessionRecord(it.record).hasSenderChain() } ?: false

    override fun deleteSession(address: SignalProtocolAddress) = dao.removeSession(account, address.name, address.deviceId)
    override fun deleteAllSessions(name: String) = dao.removeSessions(account, name)

    override fun archiveSession(address: SignalProtocolAddress) {
        val row = dao.session(account, address.name, address.deviceId) ?: return
        val record = SessionRecord(row.record)
        record.archiveCurrentState()
        storeSession(address, record)
    }

    override fun getAllAddressesWithActiveSessions(names: List<String>): Map<SignalProtocolAddress, SessionRecord> =
        names.chunked(500).flatMap { dao.sessions(account, it) }
            .map { SignalProtocolAddress(it.address, it.deviceId) to SessionRecord(it.record) }
            .filter { it.second.hasSenderChain() }
            .toMap()

    // One-time prekeys
    override fun loadPreKey(id: Int): PreKeyRecord =
        keys.preKey(account, id)?.let { PreKeyRecord(it.record) } ?: throw InvalidKeyIdException("No prekey $id")
    override fun storePreKey(id: Int, record: PreKeyRecord) = keys.putPreKey(PreKeyRow(account, id, record.serialize()))
    override fun containsPreKey(id: Int): Boolean = keys.preKey(account, id) != null
    override fun removePreKey(id: Int) = keys.removePreKey(account, id)
    override fun markAllOneTimeEcPreKeysStaleIfNecessary(staleTime: Long) = keys.markPreKeysStale(account, staleTime)
    override fun deleteAllStaleOneTimeEcPreKeys(threshold: Long, minCount: Int) = keys.deleteStalePreKeys(account, threshold, minCount)

    // Signed prekeys
    override fun loadSignedPreKey(id: Int): SignedPreKeyRecord =
        keys.signed(account, id)?.let { SignedPreKeyRecord(it.record) } ?: throw InvalidKeyIdException("No signed prekey $id")
    override fun loadSignedPreKeys(): List<SignedPreKeyRecord> = keys.allSigned(account).map { SignedPreKeyRecord(it.record) }
    override fun storeSignedPreKey(id: Int, record: SignedPreKeyRecord) =
        keys.putSigned(SignedPreKeyRow(account, id, record.serialize(), record.timestamp))
    override fun containsSignedPreKey(id: Int): Boolean = keys.signed(account, id) != null
    override fun removeSignedPreKey(id: Int) = keys.removeSigned(account, id)

    // Kyber prekeys
    override fun loadKyberPreKey(id: Int): KyberPreKeyRecord =
        keys.kyber(account, id)?.let { KyberPreKeyRecord(it.record) } ?: throw InvalidKeyIdException("No Kyber prekey $id")
    override fun loadKyberPreKeys(): List<KyberPreKeyRecord> = keys.allKyber(account).map { KyberPreKeyRecord(it.record) }
    override fun storeKyberPreKey(id: Int, record: KyberPreKeyRecord) =
        keys.putKyber(KyberPreKeyRow(account, id, record.serialize(), record.timestamp, lastResort = false))
    override fun containsKyberPreKey(id: Int): Boolean = keys.kyber(account, id) != null
    override fun storeLastResortKyberPreKey(id: Int, record: KyberPreKeyRecord) =
        keys.putKyber(KyberPreKeyRow(account, id, record.serialize(), record.timestamp, lastResort = true))
    override fun loadLastResortKyberPreKeys(): List<KyberPreKeyRecord> = keys.lastResortKyber(account).map { KyberPreKeyRecord(it.record) }
    override fun removeKyberPreKey(id: Int) = keys.removeKyber(account, id)
    override fun markAllOneTimeKyberPreKeysStaleIfNecessary(staleTime: Long) = keys.markKyberStale(account, staleTime)
    override fun deleteAllStaleOneTimeKyberPreKeys(threshold: Long, minCount: Int) = keys.deleteStaleKyber(account, threshold, minCount)

    override fun markKyberPreKeyUsed(kyberPreKeyId: Int, signedPreKeyId: Int, baseKey: ECPublicKey) {
        val row = keys.kyber(account, kyberPreKeyId) ?: return
        if (!row.lastResort) {
            keys.removeKyber(account, kyberPreKeyId)
            return
        }
        try {
            keys.putKyberUsed(KyberUsedRow(account, kyberPreKeyId, signedPreKeyId, baseKey.serialize()))
        } catch (e: android.database.sqlite.SQLiteConstraintException) {
            throw ReusedBaseKeyException("Base key was already used with last-resort Kyber prekey $kyberPreKeyId")
        }
    }

    // Sender keys (group messages)
    override fun storeSenderKey(sender: SignalProtocolAddress, distributionId: UUID, record: SenderKeyRecord) =
        dao.putSenderKey(SenderKeyRow(sender.name, sender.deviceId, distributionId.toString(), record.serialize(), System.currentTimeMillis()))

    override fun loadSenderKey(sender: SignalProtocolAddress, distributionId: UUID): SenderKeyRecord? =
        dao.senderKey(sender.name, sender.deviceId, distributionId.toString())?.let { SenderKeyRecord(it.record) }

    override fun getSenderKeySharedWith(distributionId: DistributionId): Set<SignalProtocolAddress> =
        dao.senderKeyShared(distributionId.toString()).map { SignalProtocolAddress(it.address, it.deviceId) }.toSet()

    override fun markSenderKeySharedWith(distributionId: DistributionId, addresses: Collection<SignalProtocolAddress>) =
        dao.putSenderKeyShared(addresses.map { SenderKeySharedRow(distributionId.toString(), it.name, it.deviceId) })

    override fun clearSenderKeySharedWith(addresses: Collection<SignalProtocolAddress>) =
        addresses.forEach { dao.clearSenderKeyShared(it.name, it.deviceId) }

    override fun isMultiDevice(): Boolean = true
    override fun setMultiDevice(isMultiDevice: Boolean) = Unit
}
