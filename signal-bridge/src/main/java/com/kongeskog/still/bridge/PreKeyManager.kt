package com.kongeskog.still.bridge

import android.util.Log
import com.kongeskog.still.bridge.db.ACCOUNT_ACI
import com.kongeskog.still.bridge.db.ACCOUNT_PNI
import com.kongeskog.still.bridge.db.StillDatabase
import kotlinx.coroutines.runBlocking
import org.signal.libsignal.net.PreKeyCounts
import org.signal.libsignal.net.RequestResult
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.whispersystems.signalservice.api.NetworkResultUtil
import org.whispersystems.signalservice.api.account.PreKeyUpload
import org.whispersystems.signalservice.api.push.ServiceIdType
import org.signal.network.exceptions.NonSuccessfulResponseCodeException

/**
 * Keeps the server stocked with this device's prekeys, which other people's phones need
 * to start a conversation with it. Ported from signal-cli 0.14.10 `helper/PreKeyHelper.java`.
 */
internal class PreKeyManager(
    private val deps: SignalDependencies,
    private val db: StillDatabase,
    private val store: AccountStore,
) {
    fun refreshIfNecessary() {
        val counts = when (val result = runBlocking { deps.keysApiV2.getPreKeyCounts() }) {
            is RequestResult.Success<*> -> result.result as PreKeyCounts
            else -> {
                Log.w(TAG, "Could not read prekey counts: ${result.javaClass.simpleName}")
                return
            }
        }
        Log.i(TAG, "Server holds ${counts.aciEcPreKeyCount}/${counts.aciKemPreKeyCount} ACI and ${counts.pniEcPreKeyCount}/${counts.pniKemPreKeyCount} PNI prekeys")
        refresh(ACCOUNT_ACI, counts.aciEcPreKeyCount, counts.aciKemPreKeyCount)
        if (store.pni != null) refresh(ACCOUNT_PNI, counts.pniEcPreKeyCount, counts.pniKemPreKeyCount)
    }

    private fun refresh(account: Int, ecCount: Int, kyberCount: Int) {
        val prefix = if (account == ACCOUNT_ACI) "aci" else "pni"
        val identity = store.identity(account) ?: return
        val protocolStore = if (account == ACCOUNT_ACI) deps.dataStore.aci() else deps.dataStore.pni()
        val now = System.currentTimeMillis()
        cleanUp(account, prefix, now)
        fun next(key: String) = store.int("$prefix.$key") ?: KeyUtil.randomPreKeyId()
        fun wrap(id: Int) = id % KeyUtil.PREKEY_MAXIMUM_ID

        val preKeyOffset = next("next_prekey_id")
        val preKeys: List<PreKeyRecord>? = if (ecCount < MINIMUM_COUNT) KeyUtil.preKeys(preKeyOffset) else null

        val signedId = next("next_signed_prekey_id")
        val signedStale = try {
            val active = store.int("$prefix.active_signed_prekey_id") ?: throw InvalidKeyIdException("none")
            protocolStore.loadSignedPreKey(active).timestamp < now - ROTATE_AGE
        } catch (e: InvalidKeyIdException) {
            true
        }
        val signed: SignedPreKeyRecord? = if (signedStale) KeyUtil.signedPreKey(signedId, identity.privateKey) else null

        val kyberOffset = next("next_kyber_prekey_id")
        val kyberKeys: List<KyberPreKeyRecord>? = if (kyberCount < MINIMUM_COUNT) KeyUtil.kyberPreKeys(kyberOffset, identity.privateKey) else null

        val lastResortStale = try {
            val active = store.int("$prefix.last_resort_kyber_prekey_id") ?: throw InvalidKeyIdException("none")
            protocolStore.loadKyberPreKey(active).timestamp < now - ROTATE_AGE
        } catch (e: InvalidKeyIdException) {
            true
        }
        val lastResortId = wrap(kyberOffset + (kyberKeys?.size ?: 0))
        val lastResort: KyberPreKeyRecord? = if (lastResortStale) KeyUtil.kyberPreKey(lastResortId, identity.privateKey) else null

        if (preKeys == null && signed == null && kyberKeys == null && lastResort == null) {
            Log.i(TAG, "$prefix prekeys are current")
            return
        }

        val type = if (account == ACCOUNT_ACI) ServiceIdType.ACI else ServiceIdType.PNI
        try {
            NetworkResultUtil.toPreKeysLegacy(deps.keysApi.setPreKeysSync(PreKeyUpload(type, signed, preKeys, lastResort, kyberKeys)))
        } catch (e: NonSuccessfulResponseCodeException) {
            // signal-cli tolerates 422 for the PNI set; anything else is a real failure.
            if (account != ACCOUNT_PNI || e.code != 422) throw e
            Log.w(TAG, "Server declined the PNI prekeys (422)")
            return
        }

        // The server has them; now make sure this device can answer for every key it advertised.
        db.runInTransaction {
            val values = mutableMapOf<String, Any?>()
            preKeys?.let { list ->
                list.forEach { protocolStore.storePreKey(it.id, it) }
                values["$prefix.next_prekey_id"] = wrap(preKeyOffset + list.size)
            }
            signed?.let {
                protocolStore.storeSignedPreKey(it.id, it)
                values["$prefix.active_signed_prekey_id"] = it.id
                values["$prefix.next_signed_prekey_id"] = wrap(it.id + 1)
            }
            var nextKyber = kyberOffset
            kyberKeys?.let { list ->
                list.forEach { protocolStore.storeKyberPreKey(it.id, it) }
                nextKyber = wrap(kyberOffset + list.size)
            }
            lastResort?.let {
                protocolStore.storeLastResortKyberPreKey(it.id, it)
                values["$prefix.last_resort_kyber_prekey_id"] = it.id
                nextKyber = wrap(it.id + 1)
            }
            values["$prefix.next_kyber_prekey_id"] = nextKyber
            store.putAll(values)
        }
        Log.i(TAG, "Uploaded $prefix prekeys: ${preKeys?.size ?: 0} one-time, ${kyberKeys?.size ?: 0} Kyber, signed=${signed != null}, lastResort=${lastResort != null}")
    }

    /**
     * Rotated-out signing keys are kept a month, in case a message encrypted to one is still
     * on its way, and then deleted. The keys in use are never touched.
     */
    private fun cleanUp(account: Int, prefix: String, now: Long) {
        val keys = db.preKeys()
        val activeSigned = store.int("$prefix.active_signed_prekey_id")
        val activeLastResort = store.int("$prefix.last_resort_kyber_prekey_id")
        val old = now - KEEP_ROTATED
        val signed = keys.allSigned(account).filter { it.timestamp < old && it.id != activeSigned }
        val lastResort = keys.lastResortKyber(account).filter { it.timestamp < old && it.id != activeLastResort }
        signed.forEach { keys.removeSigned(account, it.id) }
        lastResort.forEach { keys.removeKyber(account, it.id) }
        if (signed.isNotEmpty() || lastResort.isNotEmpty()) Log.i(TAG, "Deleted ${signed.size + lastResort.size} old $prefix signing keys")
    }

    private companion object {
        const val KEEP_ROTATED = 30L * 24 * 60 * 60 * 1000
        const val TAG = "StillKeys"
        const val MINIMUM_COUNT = 10
        const val ROTATE_AGE = 2L * 24 * 60 * 60 * 1000
    }
}
