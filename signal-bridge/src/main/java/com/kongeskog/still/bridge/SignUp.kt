package com.kongeskog.still.bridge

import android.util.Log
import com.kongeskog.still.bridge.db.ACCOUNT_ACI
import com.kongeskog.still.bridge.db.ACCOUNT_PNI
import com.kongeskog.still.bridge.db.KyberPreKeyRow
import com.kongeskog.still.bridge.db.SignedPreKeyRow
import com.kongeskog.still.bridge.db.StillDatabase
import org.signal.core.models.AccountEntropyPool
import org.signal.libsignal.net.RequestResult
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.util.KeyHelper
import org.signal.libsignal.zkgroup.profiles.ProfileKey
import org.signal.network.api.RegistrationApiV2
import org.signal.network.crypto.UnidentifiedAccess
import org.signal.network.rest.SignalRestClient
import java.util.Locale

/** What registering a new number needs from the user next. */
sealed interface SignUpStep {
    /** Signal wants proof of a human: show its captcha page and pass the token to [SignUp.captcha]. */
    data object Captcha : SignUpStep
    /** A code is on its way by SMS (or a call); pass it to [SignUp.code]. */
    data object EnterCode : SignUpStep
    /** The number is verified; ask for a name and call [SignUp.finish]. */
    data object EnterName : SignUpStep
    /** The account exists and is stored here. Restart the app. */
    data object Done : SignUpStep
    data class Failed(val reason: String) : SignUpStep
}

/**
 * Registers a phone number that has no Signal account, making Still its only device.
 * A verification session is opened for the number, the user proves they hold it with a code
 * sent by SMS, and the account is created with keys made here.
 *
 * Ported from signal-cli 0.14.10 `RegistrationManagerImpl` and `NumberVerificationUtils`.
 * Not supported: numbers protected by a registration-lock PIN, and setting a PIN.
 */
class SignUp internal constructor(private val db: StillDatabase, private val store: AccountStore) {
    private val api by lazy { RegistrationApiV2(SignalRestClient(SignalEnvironment.configuration, SignalEnvironment.USER_AGENT), true) }
    private var session: String? = null
    private var number: String? = null

    /** [e164] is the full number with country code, such as +15550100. */
    suspend fun start(e164: String): SignUpStep = attempt {
        number = e164
        when (val result = api.createVerificationSession(e164, null, null, null)) {
            is RequestResult.Success -> next(result.result)
            is RequestResult.NonSuccess -> SignUpStep.Failed(
                if (result.error is RegistrationApiV2.CreateSessionError.RateLimited) "Signal asks to wait before trying this number again." else "Signal did not accept that number.",
            )
            else -> offline(result)
        }
    }

    /** [token] is what Signal's captcha page hands back, with or without its `signalcaptcha://` prefix. */
    suspend fun captcha(token: String): SignUpStep = attempt {
        val id = session ?: return@attempt SignUpStep.Failed("Start again.")
        when (val result = api.updateVerificationSession(sessionId = id, captchaToken = token.removePrefix("signalcaptcha://"))) {
            is RequestResult.Success -> next(result.result)
            is RequestResult.NonSuccess -> SignUpStep.Failed("Signal did not accept the captcha. Try it again.")
            else -> offline(result)
        }
    }

    /** Asks for the code again, by voice call if [voice]. */
    suspend fun resend(voice: Boolean): SignUpStep = attempt { requestCode(voice) }

    private suspend fun next(metadata: RegistrationApiV2.SessionMetadata): SignUpStep {
        session = metadata.id
        return when {
            metadata.verified -> SignUpStep.EnterName
            metadata.allowedToRequestCode -> requestCode(false)
            "captcha" in metadata.requestedInformation -> SignUpStep.Captcha
            else -> SignUpStep.Failed("Signal is not sending codes to this number right now. Try again later.")
        }
    }

    private suspend fun requestCode(voice: Boolean): SignUpStep {
        val id = session ?: return SignUpStep.Failed("Start again.")
        val transport = if (voice) RegistrationApiV2.VerificationCodeTransport.VOICE else RegistrationApiV2.VerificationCodeTransport.SMS
        return when (val result = api.requestVerificationCode(id, Locale.getDefault(), false, transport)) {
            is RequestResult.Success -> SignUpStep.EnterCode
            is RequestResult.NonSuccess -> when (result.error) {
                is RegistrationApiV2.RequestVerificationCodeError.RateLimited -> SignUpStep.Failed("Signal asks to wait before sending another code.")
                is RegistrationApiV2.RequestVerificationCodeError.MissingRequestInformationOrAlreadyVerified -> SignUpStep.Captcha
                is RegistrationApiV2.RequestVerificationCodeError.CouldNotFulfillWithRequestedTransport ->
                    SignUpStep.Failed(if (voice) "A call is not available for this number." else "A text is not available for this number. Try a call.")
                else -> SignUpStep.Failed("Signal could not send a code.")
            }
            else -> offline(result)
        }
    }

    suspend fun code(code: String): SignUpStep = attempt {
        val id = session ?: return@attempt SignUpStep.Failed("Start again.")
        when (val result = api.submitVerificationCode(id, code.filter(Char::isDigit))) {
            is RequestResult.Success -> if (result.result.verified) SignUpStep.EnterName else SignUpStep.Failed("That code is not right.")
            is RequestResult.NonSuccess -> when (result.error) {
                is RegistrationApiV2.SubmitVerificationCodeError.SessionAlreadyVerifiedOrNoCodeRequested -> SignUpStep.EnterName
                is RegistrationApiV2.SubmitVerificationCodeError.RateLimited -> SignUpStep.Failed("Too many tries. Wait a while.")
                else -> SignUpStep.Failed("That code is not right.")
            }
            else -> offline(result)
        }
    }

    /** Creates the account under [name] and stores it. Nothing is stored unless Signal says yes. */
    suspend fun finish(name: String): SignUpStep = attempt {
        val id = session ?: return@attempt SignUpStep.Failed("Start again.")
        val pool = AccountEntropyPool.generate()
        val aciIdentity = IdentityKeyPair.generate()
        val pniIdentity = IdentityKeyPair.generate()
        val profileKey = KeyUtil.secretBytes(32)
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

        val response = api.registerAccount(
            id, password, null, null, null,
            RegistrationApiV2.AccountAttributes(
                null, aciRegistrationId, false, false, true, null,
                UnidentifiedAccess.deriveAccessKeyFrom(ProfileKey(profileKey)), false, true,
                RegistrationApiV2.AccountAttributes.Capabilities(true, true, true, true, true, true),
                null, pniRegistrationId, pool.deriveMasterKey().deriveRegistrationRecoveryPassword(),
            ),
            RegistrationApiV2.PreKeyCollection(aciIdentity.publicKey, aciSigned, aciKyber),
            RegistrationApiV2.PreKeyCollection(pniIdentity.publicKey, pniSigned, pniKyber),
            null, true, null, null, null,
        )
        val account = when (response) {
            is RequestResult.Success -> response.result
            is RequestResult.NonSuccess -> {
                Log.w(TAG, "Registration refused: ${response.error.javaClass.simpleName}")
                return@attempt SignUpStep.Failed(
                    when (response.error) {
                        is RegistrationApiV2.RegisterAccountError.RegistrationLock, is RegistrationApiV2.RegisterAccountError.TwoFactorRequired ->
                            "This number is protected by a Signal PIN. Register it in the Signal app, then link Still."
                        is RegistrationApiV2.RegisterAccountError.RateLimited -> "Signal asks to wait before trying again."
                        is RegistrationApiV2.RegisterAccountError.SessionNotFoundOrNotVerified -> "The code has expired. Start again."
                        else -> "Signal refused to create the account."
                    },
                )
            }
            else -> return@attempt offline(response)
        }

        db.runInTransaction {
            val next = { value: Int -> (value + 1) % KeyUtil.PREKEY_MAXIMUM_ID }
            store.putAll(
                mapOf(
                    AccountStore.ACI to account.aci,
                    AccountStore.PNI to account.pni,
                    AccountStore.NUMBER to (account.e164?.ifEmpty { null } ?: number),
                    AccountStore.PASSWORD to password,
                    AccountStore.PROFILE_KEY to profileKey,
                    AccountStore.ACCOUNT_ENTROPY_POOL to pool.value,
                    AccountStore.AUTH_CREDENTIAL_SALT to account.authCredentialSalt?.let { java.util.Base64.getDecoder().decode(it) },
                    AccountStore.READ_RECEIPTS to true,
                    "aci.identity.public" to aciIdentity.publicKey.serialize(),
                    "aci.identity.private" to aciIdentity.privateKey.serialize(),
                    "pni.identity.public" to pniIdentity.publicKey.serialize(),
                    "pni.identity.private" to pniIdentity.privateKey.serialize(),
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
                    // Uploaded as the profile name once the new account first connects.
                    PENDING_NAME to name.trim(),
                    AccountStore.DEVICE_ID to PrimaryRegistration.PRIMARY_DEVICE_ID,
                ),
            )
            val keys = db.preKeys()
            keys.putSigned(SignedPreKeyRow(ACCOUNT_ACI, aciSignedId, aciSigned.serialize(), aciSigned.timestamp))
            keys.putKyber(KyberPreKeyRow(ACCOUNT_ACI, aciKyberId, aciKyber.serialize(), aciKyber.timestamp, lastResort = true))
            keys.putSigned(SignedPreKeyRow(ACCOUNT_PNI, pniSignedId, pniSigned.serialize(), pniSigned.timestamp))
            keys.putKyber(KyberPreKeyRow(ACCOUNT_PNI, pniKyberId, pniKyber.serialize(), pniKyber.timestamp, lastResort = true))
        }
        Log.i(TAG, "Registered a new account")
        SignUpStep.Done
    }

    private fun offline(result: RequestResult<*, *>): SignUpStep {
        Log.w(TAG, "Request failed: ${result.javaClass.simpleName}")
        return SignUpStep.Failed("No connection to Signal. Check the network and try again.")
    }

    private suspend fun attempt(block: suspend () -> SignUpStep): SignUpStep = try {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { block() }
    } catch (e: Exception) {
        Log.w(TAG, "Sign-up step failed: ${e.javaClass.simpleName}")
        SignUpStep.Failed("Something went wrong. Try again.")
    }

    internal companion object {
        private const val TAG = "StillSignUp"
        const val PENDING_NAME = "profile.pending_name"
        /** Signal's page for the registration captcha; it redirects to a `signalcaptcha://` address holding the token. */
        const val CAPTCHA_URL = "https://signalcaptchas.org/registration/generate.html"
    }
}
