package com.kongeskog.still.bridge

import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.ecc.ECPrivateKey
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.util.Medium
import java.security.SecureRandom
import java.util.Base64

/** Key generation, ported from signal-cli `util/KeyUtils.java`. */
internal object KeyUtil {
    private val random = SecureRandom()

    val PREKEY_MAXIMUM_ID: Int = Medium.MAX_VALUE

    fun secretBytes(size: Int) = ByteArray(size).also(random::nextBytes)

    fun createPassword(): String = Base64.getEncoder().encodeToString(secretBytes(18))

    fun randomPreKeyId(): Int = random.nextInt(PREKEY_MAXIMUM_ID)

    const val PREKEY_BATCH_SIZE = 100

    fun preKeys(offset: Int): List<PreKeyRecord> =
        (0 until PREKEY_BATCH_SIZE).map { PreKeyRecord((offset + it) % PREKEY_MAXIMUM_ID, ECKeyPair.generate()) }

    fun kyberPreKeys(offset: Int, identityPrivateKey: ECPrivateKey): List<KyberPreKeyRecord> =
        (0 until PREKEY_BATCH_SIZE).map { kyberPreKey((offset + it) % PREKEY_MAXIMUM_ID, identityPrivateKey) }

    fun signedPreKey(id: Int, identityPrivateKey: ECPrivateKey): SignedPreKeyRecord {
        val keyPair = ECKeyPair.generate()
        val signature = identityPrivateKey.calculateSignature(keyPair.publicKey.serialize())
        return SignedPreKeyRecord(id, System.currentTimeMillis(), keyPair, signature)
    }

    fun kyberPreKey(id: Int, identityPrivateKey: ECPrivateKey): KyberPreKeyRecord {
        val keyPair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        val signature = identityPrivateKey.calculateSignature(keyPair.publicKey.serialize())
        return KyberPreKeyRecord(id, System.currentTimeMillis(), keyPair, signature)
    }
}
