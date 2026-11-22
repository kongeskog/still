package com.kongeskog.still.bridge

import android.util.Log
import com.kongeskog.still.bridge.db.ACCOUNT_ACI
import com.kongeskog.still.bridge.db.ACCOUNT_PNI
import com.kongeskog.still.bridge.db.KyberPreKeyRow
import com.kongeskog.still.bridge.db.SignedPreKeyRow
import com.kongeskog.still.bridge.db.StillDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.signal.core.models.ServiceId.ACI
import org.signal.core.models.ServiceId.PNI
import org.signal.core.util.crypto.DeviceNameCipher
import org.signal.libsignal.net.RequestResult
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.ecc.ECPrivateKey
import org.signal.libsignal.protocol.util.KeyHelper
import org.signal.network.api.RegistrationApiV2
import org.signal.network.rest.SignalRestClient
import org.whispersystems.signalservice.api.provisioning.ProvisioningSocket
import org.whispersystems.signalservice.internal.crypto.SecondaryProvisioningCipher.ProvisioningDecryptResult
import org.whispersystems.signalservice.internal.push.ProvisionMessage
import java.io.IOException
import java.util.Base64

sealed interface LinkState {
    data object Connecting : LinkState
    /** Show [url] as a QR code for the official app to scan. It is replaced when it expires. */
    data class ShowCode(val url: String) : LinkState
    data object Registering : LinkState
    data object Linked : LinkState
    data class Failed(val reason: String) : LinkState
}

/**
 * Links this phone to an existing Signal account as a secondary device.
 * Ported from signal-cli 0.14.10 `internal/ProvisioningManagerImpl.java`.
 */
internal class LinkingManager(private val db: StillDatabase, private val store: AccountStore) {

    /**
     * A link code lives for 90 seconds, after which the library's socket fails. A fresh one
     * is issued each time, for about ten minutes, so the user is never scanning a dead code.
     */
    fun link(deviceName: String): Flow<LinkState> = flow {
        emit(LinkState.Connecting)
        repeat(MAX_CODES) {
            var approval: ProvisionMessage? = null
            var failure: String? = null
            oneCode().collect { event ->
                when (event) {
                    is CodeEvent.Shown -> emit(LinkState.ShowCode(event.url))
                    is CodeEvent.Approved -> approval = event.message
                    is CodeEvent.Failed -> failure = event.reason
                    CodeEvent.Expired -> Unit // issue the next code
                }
            }
            failure?.let { emit(LinkState.Failed(it)); return@flow }
            approval?.let { message ->
                emit(LinkState.Registering)
                // Registration runs here, not inside the socket's block: the library cancels
                // that block's scope when the socket closes, which is right after the approval
                // arrives. NonCancellable because the approval's code can only be used once.
                val state = try {
                    withContext(NonCancellable) { register(message, deviceName) }
                    LinkState.Linked
                } catch (e: Exception) {
                    Log.w(TAG, "Linking failed", e)
                    LinkState.Failed(describe(e))
                }
                emit(state)
                return@flow
            }
        }
        emit(LinkState.Failed("No approval arrived from Signal."))
    }.flowOn(Dispatchers.IO)

    private sealed interface CodeEvent {
        data class Shown(val url: String) : CodeEvent
        data class Approved(val message: ProvisionMessage) : CodeEvent
        data class Failed(val reason: String) : CodeEvent
        data object Expired : CodeEvent
    }

    /** Opens one provisioning socket and reports its single code and how it ended. */
    private fun oneCode(): Flow<CodeEvent> = callbackFlow {
        var codeShown = false
        val socket = ProvisioningSocket.start<ProvisionMessage>(
            // True tells the primary device that Still can take a transfer of message history.
            ProvisioningSocket.Mode.Link(true),
            IdentityKeyPair.generate(),
            SignalEnvironment.configuration,
            { _, t ->
                // After a code was shown this is its 90 second expiry; before, a real failure.
                Log.w(TAG, "Provisioning socket ended: ${describe(t)}")
                trySend(if (codeShown) CodeEvent.Expired else CodeEvent.Failed(describe(t)))
                close()
            },
        ) { socket ->
            val url = socket.getProvisioningUrl()
            // Logged so the code can be shown on a computer screen: Signal on this same
            // phone has to scan it with the camera. It holds only this request's public key.
            Log.i(TAG, "Link code: $url")
            codeShown = true
            trySend(CodeEvent.Shown(url))
            val result = socket.getProvisioningMessageDecryptResult()
            Log.i(TAG, "Approval received")
            trySend(
                if (result is ProvisioningDecryptResult.Success) CodeEvent.Approved(result.message)
                else CodeEvent.Failed("Could not read the linking message"),
            )
            close()
        }
        awaitClose { socket.close() }
    }

    private suspend fun register(msg: ProvisionMessage, deviceName: String) {
        val number = msg.number
        val aci = ACI.parseOrThrow(msg.aci, msg.aciBinary)
        val pni = if (number != null) PNI.parseOrThrow(msg.pni, msg.pniBinary) else null

        val aciIdentity = identity(msg.aciIdentityKeyPublic, msg.aciIdentityKeyPrivate)
            ?: throw IOException("Linking message has no identity key")
        val pniIdentity = if (pni != null) identity(msg.pniIdentityKeyPublic, msg.pniIdentityKeyPrivate) else null
        val profileKey = msg.profileKey?.toByteArray() ?: KeyUtil.secretBytes(32)
        val password = KeyUtil.createPassword()
        val encryptedName = Base64.getEncoder().withoutPadding()
            .encodeToString(DeviceNameCipher.encryptDeviceName(deviceName.toByteArray(), aciIdentity))

        val aciRegistrationId = KeyHelper.generateRegistrationId(false)
        val pniRegistrationId = KeyHelper.generateRegistrationId(false)
        val aciSignedId = KeyUtil.randomPreKeyId()
        val aciKyberId = KeyUtil.randomPreKeyId()
        val aciSigned = KeyUtil.signedPreKey(aciSignedId, aciIdentity.privateKey)
        val aciKyber = KeyUtil.kyberPreKey(aciKyberId, aciIdentity.privateKey)
        val pniSignedId = KeyUtil.randomPreKeyId()
        val pniKyberId = KeyUtil.randomPreKeyId()
        val pniSigned = pniIdentity?.let { KeyUtil.signedPreKey(pniSignedId, it.privateKey) }
        val pniKyber = pniIdentity?.let { KeyUtil.kyberPreKey(pniKyberId, it.privateKey) }

        // Same capability set signal-cli declares for a linked device.
        val capabilities = RegistrationApiV2.AccountAttributes.Capabilities(true, true, true, true, true, true)
        val attributes = RegistrationApiV2.DeviceAttributes(
            true, aciRegistrationId, pniRegistrationId, encryptedName, capabilities,
        )
        val api = RegistrationApiV2(SignalRestClient(SignalEnvironment.configuration, SignalEnvironment.USER_AGENT), false)
        val response = api.registerAsSecondaryDevice(
            aci, password, msg.provisioningCode ?: throw IOException("Linking message has no code"), attributes,
            RegistrationApiV2.PreKeyCollection(aciIdentity.publicKey, aciSigned, aciKyber),
            if (pniIdentity != null) RegistrationApiV2.PreKeyCollection(pniIdentity.publicKey, pniSigned!!, pniKyber!!) else null,
            null,
        )
        val deviceId = when (response) {
            is RequestResult.Success -> response.result.deviceId
            is RequestResult.NonSuccess -> throw IOException("Signal refused the link: ${response.error.javaClass.simpleName}")
            is RequestResult.RetryableNetworkError -> throw response.networkError
            is RequestResult.ApplicationError -> throw IOException("Linking failed", response.cause)
        }

        // The server now knows this device; store everything in one transaction so a crash
        // cannot leave a half-written account.
        db.runInTransaction {
            // A link makes a new device. Whatever an earlier link left behind (sessions, prekeys,
            // undelivered envelopes) belongs to the old one and would only confuse this one.
            // Conversations and messages stay.
            val raw = db.openHelper.writableDatabase
            for (table in listOf("session", "sender_key", "sender_key_shared", "prekey", "signed_prekey", "kyber_prekey", "kyber_used", "envelope_cache")) {
                raw.execSQL("DELETE FROM `$table`")
            }
            raw.execSQL("DELETE FROM key_value WHERE `key` IN ('storage.version', 'contacts.requested_at') OR `key` LIKE '%prekey_id'")
            val next = { id: Int -> (id + 1) % KeyUtil.PREKEY_MAXIMUM_ID }
            store.putAll(
                mapOf(
                    AccountStore.ACI to aci.toString(),
                    AccountStore.PNI to pni?.toString(),
                    AccountStore.NUMBER to number,
                    AccountStore.PASSWORD to password,
                    AccountStore.DEVICE_NAME to encryptedName,
                    AccountStore.PROFILE_KEY to profileKey,
                    AccountStore.ACCOUNT_ENTROPY_POOL to msg.accountEntropyPool,
                    AccountStore.MEDIA_ROOT_BACKUP_KEY to msg.mediaRootBackupKey?.toByteArray(),
                    AccountStore.AUTH_CREDENTIAL_SALT to msg.authCredentialSalt?.toByteArray(),
                    AccountStore.READ_RECEIPTS to msg.readReceipts,
                    // Present only if the user chose to transfer history; consumed by HistoryImport.
                    HistoryImport.KEY to msg.ephemeralBackupKey?.toByteArray(),
                    "aci.identity.public" to aciIdentity.publicKey.serialize(),
                    "aci.identity.private" to aciIdentity.privateKey.serialize(),
                    "pni.identity.public" to pniIdentity?.publicKey?.serialize(),
                    "pni.identity.private" to pniIdentity?.privateKey?.serialize(),
                    AccountStore.ACI_REGISTRATION_ID to aciRegistrationId,
                    AccountStore.PNI_REGISTRATION_ID to pniRegistrationId,
                    AccountStore.ACI_ACTIVE_SIGNED_PREKEY_ID to aciSignedId,
                    AccountStore.ACI_NEXT_SIGNED_PREKEY_ID to next(aciSignedId),
                    AccountStore.ACI_LAST_RESORT_KYBER_PREKEY_ID to aciKyberId,
                    AccountStore.ACI_NEXT_KYBER_PREKEY_ID to next(aciKyberId),
                    AccountStore.PNI_ACTIVE_SIGNED_PREKEY_ID to pniSigned?.let { pniSignedId },
                    AccountStore.PNI_NEXT_SIGNED_PREKEY_ID to next(pniSignedId),
                    AccountStore.PNI_LAST_RESORT_KYBER_PREKEY_ID to pniKyber?.let { pniKyberId },
                    AccountStore.PNI_NEXT_KYBER_PREKEY_ID to next(pniKyberId),
                    // Written last in the map but in the same transaction: its presence means "linked".
                    AccountStore.DEVICE_ID to deviceId,
                ),
            )
            val keys = db.preKeys()
            keys.putSigned(SignedPreKeyRow(ACCOUNT_ACI, aciSignedId, aciSigned.serialize(), aciSigned.timestamp))
            keys.putKyber(KyberPreKeyRow(ACCOUNT_ACI, aciKyberId, aciKyber.serialize(), aciKyber.timestamp, lastResort = true))
            if (pniSigned != null && pniKyber != null) {
                keys.putSigned(SignedPreKeyRow(ACCOUNT_PNI, pniSignedId, pniSigned.serialize(), pniSigned.timestamp))
                keys.putKyber(KyberPreKeyRow(ACCOUNT_PNI, pniKyberId, pniKyber.serialize(), pniKyber.timestamp, lastResort = true))
            }
        }
        Log.i(TAG, "Linked as device $deviceId")
    }

    private fun identity(public: okio.ByteString?, private: okio.ByteString?): IdentityKeyPair? {
        if (public == null || private == null) return null
        return IdentityKeyPair(IdentityKey(public.toByteArray()), ECPrivateKey(private.toByteArray()))
    }

    private fun describe(t: Throwable): String = t.message ?: t.javaClass.simpleName

    private companion object {
        const val TAG = "StillLink"
        // Half an hour of codes: enough to read the steps and fetch another phone.
        const val MAX_CODES = 20
    }
}
