package com.kongeskog.still.bridge

import android.util.Log
import com.kongeskog.still.bridge.db.ACCOUNT_ACI
import com.kongeskog.still.bridge.db.ACCOUNT_PNI
import org.signal.core.models.AccountEntropyPool
import org.signal.core.models.ServiceId
import org.signal.core.models.backup.MediaRootBackupKey
import org.signal.libsignal.net.RequestResult
import org.signal.libsignal.protocol.InvalidKeyException
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.zkgroup.profiles.ProfileKey
import org.signal.network.NetworkResult
import org.signal.network.api.LinkDeviceApi
import java.util.Base64

/**
 * Adds another device to this account, from the account's main device: walk the QR
 * code Signal Desktop shows, confirm, and it is linked. Ported from kotozute's
 * `DesktopLinker.add`, which in turn follows Signal's `LinkDeviceRepository`.
 *
 * Only the main device may do this: a linked device holds no account entropy pool
 * and has no say over who else joins.
 */
/** One other device on the account, as the Linked devices screen lists it. */
data class LinkedDevice(val id: Int, val name: String, val lastSeen: Long)

internal class DeviceLinker(private val deps: SignalDependencies, private val store: AccountStore) {

    sealed interface Outcome {
        data object Linked : Outcome
        /** Not a Signal link code, or one whose key cannot be read. */
        data object BadCode : Outcome
        data object LimitReached : Outcome
        /** This phone is not the account's main device. */
        data object NotPrimary : Outcome
        data class Failed(val why: String) : Outcome
    }

    

    val isPrimary: Boolean get() = store.deviceId == PRIMARY

    fun add(text: String): Outcome {
        val (deviceIdentifier, deviceKey) = parse(text) ?: return Outcome.BadCode
        if (!isPrimary) return Outcome.NotPrimary
        val aci = ServiceId.ACI.parseOrNull(store.aci ?: return Outcome.NotPrimary) ?: return Outcome.NotPrimary
        val pni = store.pni?.let { ServiceId.PNI.parseOrNull(it) }
        val aciIdentity = store.identity(ACCOUNT_ACI) ?: return Outcome.NotPrimary
        val pniIdentity = store.identity(ACCOUNT_PNI)
        val profileKey = store.bytes(AccountStore.PROFILE_KEY)
            ?.let { runCatching { ProfileKey(it) }.getOrNull() } ?: return Outcome.NotPrimary
        val pool = store.string(AccountStore.ACCOUNT_ENTROPY_POOL)
            ?.let(::AccountEntropyPool) ?: return Outcome.NotPrimary
        // A key this account hands to each new device; generated once and kept, exactly as
        // kotozute does, so a device linked later gets the same key as an earlier one.
        val mediaRoot = store.bytes(AccountStore.MEDIA_ROOT_BACKUP_KEY)
            ?.let { runCatching { MediaRootBackupKey(it) }.getOrNull() }
            ?: MediaRootBackupKey.generate().also {
                store.putAll(mapOf(AccountStore.MEDIA_ROOT_BACKUP_KEY to it.value))
            }

        val api = LinkDeviceApi(deps.authenticatedWebSocket)
        val code = when (val r = api.getDeviceVerificationCode()) {
            is NetworkResult.Success -> r.result.verificationCode
            is NetworkResult.StatusCodeError ->
                return if (r.code == LIMIT_REACHED) Outcome.LimitReached else Outcome.Failed("code ${r.code}")
            is NetworkResult.NetworkError -> return Outcome.Failed("network")
            is NetworkResult.ApplicationError -> return Outcome.Failed("application")
        }

        val result = try {
            api.linkDevice(
                e164 = store.number,
                aci = aci,
                pni = pni,
                deviceIdentifier = deviceIdentifier,
                deviceKey = deviceKey,
                aciIdentityKeyPair = aciIdentity,
                pniIdentityKeyPair = pniIdentity,
                profileKey = profileKey,
                accountEntropyPool = pool,
                masterKey = pool.deriveMasterKey(),
                mediaRootBackupKey = mediaRoot,
                code = code,
                ephemeralMessageBackupKey = null,
                authCredentialSalt = store.bytes(AccountStore.AUTH_CREDENTIAL_SALT),
            )
        } catch (e: InvalidKeyException) {
            Log.w(TAG, "the code's key could not be used", e)
            return Outcome.BadCode
        }

        return when (result) {
            is NetworkResult.Success -> Outcome.Linked
            is NetworkResult.StatusCodeError ->
                if (result.code == LIMIT_REACHED) Outcome.LimitReached else Outcome.Failed("code ${result.code}")
            is NetworkResult.NetworkError -> Outcome.Failed("network")
            is NetworkResult.ApplicationError -> Outcome.Failed("application")
        }
    }

    /** The other devices, or null when the server could not be asked. */
    suspend fun devices(): List<LinkedDevice>? {
        val r = LinkDeviceApi(deps.authenticatedWebSocket).getDevices()
        val list = (r as? RequestResult.Success)?.result ?: return null
        val identity = store.identity(ACCOUNT_ACI) ?: return null
        return list.map { d ->
            val name = runCatching {
                org.signal.core.util.crypto.DeviceNameCipher.decryptDeviceName(
                    org.signal.core.util.crypto.DeviceName.ADAPTER.decode(d.encryptedName),
                    identity,
                )?.let { String(it) }
            }.getOrNull()
            LinkedDevice(d.id, name.orEmpty(), d.lastSeen.toEpochMilli())
        }.filter { it.id != store.deviceId }
            .sortedBy { it.id }
    }

    /** True when the device is gone. The main device itself is never removed from here. */
    suspend fun remove(deviceId: Int): Boolean {
        if (deviceId == PRIMARY) return false
        val result = LinkDeviceApi(deps.authenticatedWebSocket).removeDevice(deviceId)
        if (result is RequestResult.NonSuccess) Log.w(TAG, "remove refused: ${result.error}")
        return result is RequestResult.Success
    }

    private fun parse(text: String): Pair<String, ECPublicKey>? {
        val uri = runCatching { android.net.Uri.parse(text.trim()) }.getOrNull() ?: return null
        if (uri.scheme != "sgnl" || uri.host != "linkdevice") return null
        val id = uri.getQueryParameter("uuid")?.takeIf { it.isNotBlank() } ?: return null
        val encoded = uri.getQueryParameter("pub_key")?.takeIf { it.isNotBlank() } ?: return null
        val key = runCatching { ECPublicKey(Base64.getDecoder().decode(encoded)) }.getOrNull() ?: return null
        return id to key
    }

    private companion object {
        const val TAG = "StillLink"
        const val PRIMARY = 1
        const val LIMIT_REACHED = 411
    }
}
