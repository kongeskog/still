package com.kongeskog.still.bridge

import android.util.Log
import com.kongeskog.still.bridge.db.ACCOUNT_ACI
import com.kongeskog.still.bridge.db.ACCOUNT_PNI
import com.kongeskog.still.bridge.db.KyberPreKeyRow
import com.kongeskog.still.bridge.db.SignedPreKeyRow
import com.kongeskog.still.bridge.db.StillDatabase
import org.signal.core.models.AccountEntropyPool
import org.signal.core.models.ServiceId.ACI
import org.signal.libsignal.net.RequestResult
import org.signal.libsignal.protocol.util.KeyHelper
import org.signal.libsignal.zkgroup.profiles.ProfileKey
import org.signal.network.api.RegistrationApiV2
import org.signal.network.crypto.UnidentifiedAccess
import org.signal.network.rest.SignalRestClient

/**
 * Turns this linked device into the account's main device. The account entropy pool Still
 * received when it was linked yields the "registration recovery password", which Signal's
 * servers accept in place of an SMS code. The identity keys stay the same, so contacts see
 * no safety-number change; whichever app was the main device before is signed out.
 *
 * Ported from signal-cli 0.14.10 `RegistrationManagerImpl.recoverAccount`.
 */
internal class PrimaryRegistration(private val db: StillDatabase, private val store: AccountStore) {

    /** Returns null on success, otherwise a short reason to show. Nothing local changes unless the server said yes. */
    suspend fun promote(): String? {
        val pool = store.string(AccountStore.ACCOUNT_ENTROPY_POOL)?.let(::AccountEntropyPool) ?: return "This link has no account key. Link again first."
        val aci = ACI.parseOrNull(store.aci) ?: return "Not linked"
        val aciIdentity = store.identity(ACCOUNT_ACI) ?: return "Not linked"
        val pniIdentity = store.identity(ACCOUNT_PNI) ?: return "This link has no phone-number identity. Link again first."
        val profileKey = store.bytes(AccountStore.PROFILE_KEY) ?: return "Not linked"

        val password = KeyUtil.createPassword()
        val aciRegistrationId = KeyHelper.generateRegistrationId(false)
        val pniRegistrationId = KeyHelper.generateRegistrationId(false)
        val aciSignedId = KeyUtil.randomPreKeyId()
        val aciKyberId = KeyUtil.randomPreKeyId()
        val pniSignedId = KeyUtil.randomPreKeyId()
        val pniKyberId = KeyUtil.randomPreKeyId()
        val aciSigned = KeyUtil.signedPreKey(aciSignedId, aciIdentity.privateKey)
        val aciKyber = KeyUtil.kyberPreKey(aciKyberId, aciIdentity.privateKey)
        val pniSigned = KeyUtil.signedPreKey(pniSignedId, pniIdentity.privateKey)
        val pniKyber = KeyUtil.kyberPreKey(pniKyberId, pniIdentity.privateKey)
        val recoveryPassword = pool.deriveMasterKey().deriveRegistrationRecoveryPassword()
        val api = RegistrationApiV2(SignalRestClient(SignalEnvironment.configuration, SignalEnvironment.USER_AGENT), true)

        suspend fun attempt(registrationLock: String?) = api.registerAccount(
            null, password, null, recoveryPassword, null,
            RegistrationApiV2.AccountAttributes(
                null, aciRegistrationId, false, false, true, registrationLock,
                UnidentifiedAccess.deriveAccessKeyFrom(ProfileKey(profileKey)), false, null,
                RegistrationApiV2.AccountAttributes.Capabilities(true, true, true, true, true, true),
                null, pniRegistrationId, recoveryPassword,
            ),
            RegistrationApiV2.PreKeyCollection(aciIdentity.publicKey, aciSigned, aciKyber),
            RegistrationApiV2.PreKeyCollection(pniIdentity.publicKey, pniSigned, pniKyber),
            null, true, aci, null, null,
        )

        var response = attempt(null)
        // An account with registration lock wants the lock token too; it comes from the same key.
        if (response is RequestResult.NonSuccess && response.error is RegistrationApiV2.RegisterAccountError.RegistrationLock) {
            response = attempt(pool.deriveMasterKey().deriveRegistrationLock())
        }
        val result = when (response) {
            is RequestResult.Success -> response.result
            is RequestResult.NonSuccess -> {
                Log.w(TAG, "Registration refused: ${response.error.javaClass.simpleName}")
                return when (response.error) {
                    is RegistrationApiV2.RegisterAccountError.RegistrationRecoveryPasswordIncorrect -> "Signal did not accept this device's account key."
                    is RegistrationApiV2.RegisterAccountError.RateLimited -> "Signal asks to wait before trying again."
                    is RegistrationApiV2.RegisterAccountError.RegistrationLock, is RegistrationApiV2.RegisterAccountError.TwoFactorRequired -> "Registration lock could not be passed."
                    else -> "Signal refused: ${response.error.javaClass.simpleName}"
                }
            }
            is RequestResult.RetryableNetworkError -> return "No connection. Nothing was changed."
            is RequestResult.ApplicationError -> {
                Log.w(TAG, "Registration failed: ${response.cause.javaClass.simpleName}")
                return "Something went wrong. Nothing was changed."
            }
        }

        // The server now treats this as device 1. One transaction, so a crash cannot leave half of it.
        db.runInTransaction {
            val raw = db.openHelper.writableDatabase
            for (table in listOf("session", "sender_key", "sender_key_shared", "prekey", "signed_prekey", "kyber_prekey", "kyber_used", "envelope_cache")) {
                raw.execSQL("DELETE FROM `$table`")
            }
            raw.execSQL("DELETE FROM key_value WHERE `key` LIKE '%prekey_id'")
            val next = { id: Int -> (id + 1) % KeyUtil.PREKEY_MAXIMUM_ID }
            store.putAll(
                mapOf(
                    AccountStore.PASSWORD to password,
                    AccountStore.DEVICE_ID to PRIMARY_DEVICE_ID,
                    AccountStore.PNI to result.pni,
                    AccountStore.NUMBER to result.e164,
                    AccountStore.AUTH_CREDENTIAL_SALT to result.authCredentialSalt?.let { java.util.Base64.getDecoder().decode(it) },
                    AccountStore.ACI_REGISTRATION_ID to aciRegistrationId,
                    AccountStore.PNI_REGISTRATION_ID to pniRegistrationId,
                    AccountStore.ACI_ACTIVE_SIGNED_PREKEY_ID to aciSignedId,
                    AccountStore.ACI_NEXT_SIGNED_PREKEY_ID to next(aciSignedId),
                    AccountStore.ACI_LAST_RESORT_KYBER_PREKEY_ID to aciKyberId,
                    AccountStore.ACI_NEXT_KYBER_PREKEY_ID to next(aciKyberId),
                    AccountStore.PNI_ACTIVE_SIGNED_PREKEY_ID to pniSignedId,
                    AccountStore.PNI_NEXT_SIGNED_PREKEY_ID to next(pniSignedId),
                    AccountStore.PNI_LAST_RESORT_KYBER_PREKEY_ID to pniKyberId,
                    AccountStore.PNI_NEXT_KYBER_PREKEY_ID to next(pniKyberId),
                ),
            )
            val keys = db.preKeys()
            keys.putSigned(SignedPreKeyRow(ACCOUNT_ACI, aciSignedId, aciSigned.serialize(), aciSigned.timestamp))
            keys.putKyber(KyberPreKeyRow(ACCOUNT_ACI, aciKyberId, aciKyber.serialize(), aciKyber.timestamp, lastResort = true))
            keys.putSigned(SignedPreKeyRow(ACCOUNT_PNI, pniSignedId, pniSigned.serialize(), pniSigned.timestamp))
            keys.putKyber(KyberPreKeyRow(ACCOUNT_PNI, pniKyberId, pniKyber.serialize(), pniKyber.timestamp, lastResort = true))
        }
        Log.i(TAG, "Registered as the main device")
        return null
    }

    companion object {
        private const val TAG = "StillRegister"
        const val PRIMARY_DEVICE_ID = 1
    }
}
