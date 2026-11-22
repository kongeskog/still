package com.kongeskog.still.bridge

import com.kongeskog.still.bridge.db.KeyValueRow
import com.kongeskog.still.bridge.db.StillDatabase
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.ecc.ECPrivateKey
import java.util.Base64

/** The account's credentials and long-term keys, kept in the `key_value` table. */
internal class AccountStore(private val db: StillDatabase) {
    private val kv get() = db.keyValue()

    fun string(key: String): String? = kv.get(key)
    fun bytes(key: String): ByteArray? = kv.get(key)?.let(Base64.getDecoder()::decode)
    fun int(key: String): Int? = kv.get(key)?.toIntOrNull()

    val isLinked: Boolean get() = (int(DEVICE_ID) ?: 0) > 0
    val aci: String? get() = string(ACI)
    val pni: String? get() = string(PNI)
    val number: String? get() = string(NUMBER)
    val deviceId: Int get() = int(DEVICE_ID) ?: 0
    val password: String? get() = string(PASSWORD)

    fun identity(account: Int): IdentityKeyPair? {
        val prefix = if (account == 0) "aci" else "pni"
        val public = bytes("$prefix.identity.public") ?: return null
        val private = bytes("$prefix.identity.private") ?: return null
        return IdentityKeyPair(IdentityKey(public), ECPrivateKey(private))
    }

    /** Writes several values at once. Byte arrays are stored as Base64; null removes nothing. */
    fun putAll(values: Map<String, Any?>) {
        kv.putAll(values.mapNotNull { (key, value) ->
            when (value) {
                null -> null
                is ByteArray -> KeyValueRow(key, Base64.getEncoder().encodeToString(value))
                else -> KeyValueRow(key, value.toString())
            }
        })
    }

    companion object {
        const val ACI = "aci"
        const val PNI = "pni"
        const val NUMBER = "number"
        const val PASSWORD = "password"
        const val DEVICE_ID = "device_id"
        const val DEVICE_NAME = "device_name.encrypted"
        const val PROFILE_KEY = "profile_key"
        const val ACCOUNT_ENTROPY_POOL = "account_entropy_pool"
        const val MEDIA_ROOT_BACKUP_KEY = "media_root_backup_key"
        const val AUTH_CREDENTIAL_SALT = "auth_credential_salt"
        const val READ_RECEIPTS = "read_receipts"
        const val ACI_REGISTRATION_ID = "aci.registration_id"
        const val PNI_REGISTRATION_ID = "pni.registration_id"
        const val ACI_NEXT_SIGNED_PREKEY_ID = "aci.next_signed_prekey_id"
        const val PNI_NEXT_SIGNED_PREKEY_ID = "pni.next_signed_prekey_id"
        const val ACI_NEXT_KYBER_PREKEY_ID = "aci.next_kyber_prekey_id"
        const val PNI_NEXT_KYBER_PREKEY_ID = "pni.next_kyber_prekey_id"
        const val ACI_ACTIVE_SIGNED_PREKEY_ID = "aci.active_signed_prekey_id"
        const val PNI_ACTIVE_SIGNED_PREKEY_ID = "pni.active_signed_prekey_id"
        const val ACI_LAST_RESORT_KYBER_PREKEY_ID = "aci.last_resort_kyber_prekey_id"
        const val PNI_LAST_RESORT_KYBER_PREKEY_ID = "pni.last_resort_kyber_prekey_id"
    }
}
