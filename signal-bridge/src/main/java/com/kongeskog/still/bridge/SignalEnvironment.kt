package com.kongeskog.still.bridge

import okhttp3.Dns
import okhttp3.Interceptor
import org.signal.libsignal.net.Network
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.network.config.SignalCdnUrl
import org.signal.network.config.SignalCdsiUrl
import org.signal.network.config.SignalProxy
import org.signal.network.config.SignalServiceConfiguration
import org.signal.network.config.SignalServiceUrl
import org.signal.network.config.SignalStorageUrl
import org.signal.network.config.SignalSvr2Url
import org.signal.network.config.TrustStore
import java.io.InputStream
import java.util.Base64
import java.util.Optional

/**
 * Signal's production service: URLs, pinned certificates and public parameters.
 * Ported from signal-cli 0.14.10 `config/LiveConfig.java`; re-check on every library bump,
 * because Signal rotates the enclave IDs.
 */
internal object SignalEnvironment {
    /**
     * signal-cli's convention: the official client version the library tracks, then our own
     * name. Bump the first part together with the library version.
     */
    const val USER_AGENT = "Signal-Android/8.35.1 Still/0.1.3"

    /**
     * The day the Signal libraries and the version claimed in [USER_AGENT] were last brought
     * up to date. Change it with every library bump, and only then: Signal's servers retire a
     * version about three months after its release, whenever the app was built.
     */
    const val LIBRARIES_UPDATED = "2026-11-21"

    val netEnvironment = Network.Environment.PRODUCTION

    const val CDSI_MRENCLAVE = "0f6fd79cdfdaa5b2e6337f534d3baf999318b0c462a7ac1f41297a3e4b424a57"
    val SVR2_MRENCLAVES = listOf(
        "fdbbacdc0c043d0d53fe1440f62728de0386f45ab0a275bd8f99e03a02af355e",
        "ced8217b26228e4b210c985786999d095c4958a94faf37b14acaf25c4cbb02a4",
    )

    val unidentifiedSenderTrustRoots: List<ECPublicKey> by lazy {
        listOf(
            "BXu6QIKVz5MA8gstzfOgRQGqyLqOwNKHL6INkv3IHWMF",
            "BUkY0I+9+oPgDCn4+Ac6Iu813yvqkDr/ga8DzLxFxuk6",
        ).map { ECPublicKey(Base64.getDecoder().decode(it)) }
    }

    private val trustStore = object : TrustStore {
        // BKS keystore from signal-cli; Android reads BKS natively.
        override fun getKeyStoreInputStream(): InputStream =
            SignalEnvironment::class.java.getResourceAsStream("whisper.store")
                ?: error("whisper.store is missing from the APK")

        override fun getKeyStorePassword(): String = "whisper"
    }

    private const val ZK_GROUP_SERVER_PUBLIC_PARAMS =
        "AMhf5ywVwITZMsff/eCyudZx9JDmkkkbV6PInzG4p8x3VqVJSFiMvnvlEKWuRob/1eaIetR31IYeAbm0NdOuHH8Qi+Rexi1wLlpzIo1gstHWBfZzy1+qHRV5A4TqPp15YzBPm0WSggW6PbSn+F4lf57VCnHF7p8SvzAA2ZZJPYJURt8X7bbg+H3i+PEjH9DXItNEqs2sNcug37xZQDLm7X36nOoGPs54XsEGzPdEV+itQNGUFEjY6X9Uv+Acuks7NpyGvCoKxGwgKgE5XyJ+nNKlyHHOLb6N1NuHyBrZrgtY/JYJHRooo5CEqYKBqdFnmbTVGEkCvJKxLnjwKWf+fEPoWeQFj5ObDjcKMZf2Jm2Ae69x+ikU5gBXsRmoF94GXTLfN0/vLt98KDPnxwAQL9j5V1jGOY8jQl6MLxEs56cwXN0dqCnImzVH3TZT1cJ8SW1BRX6qIVxEzjsSGx3yxF3suAilPMqGRp4ffyopjMD1JXiKR2RwLKzizUe5e8XyGOy9fplzhw3jVzTRyUZTRSZKkMLWcQ/gv0E4aONNqs4P+NameAZYOD12qRkxosQQP5uux6B2nRyZ7sAV54DgFyLiRcq1FvwKw2EPQdk4HDoePrO/RNUbyNddnM/mMgj4FW65xCoT1LmjrIjsv/Ggdlx46ueczhMgtBunx1/w8k8V+l8LVZ8gAT6wkU5J+DPQalQguMg12Jzug3q4TbdHiGCmD9EunCwOmsLuLJkz6EcSYXtrlDEnAM+hicw7iergYLLlMXpfTdGxJCWJmP4zqUFeTTmsmhsjGBt7NiEB/9pFFEB3pSbf4iiUukw63Eo8Aqnf4iwob6X1QviCWuc8t0LUlT9vALgh/f2DPVOOmR0RW6bgRvc7DSF20V/omg+YBw=="
    private const val GENERIC_SERVER_PUBLIC_PARAMS =
        "AeCO67P9mIv1yUHkdeZ9JF789GDbox61GvTqq3S4kYc1ADUWxWHQygU390tv1oRWt9WjkdZlU7mKkifF59ftjE+2ZlMmxns6I+ySiLpR8FEmfu+TGpVp3zYTjNV93obJJTyBCCsSHVETCyQRbKdCyb5TMa6LGrvcZaX0Q/VAavhuNA/m4kSiRMgSnYrUjGhVekdDnF+7xioo4wvFnxjIDh7uJQrYOWD6MloNGX7St5gbysTuQQ7i/HI38b9V8x8mKazuDSXxB//BKGZx/XHkK8cHX+QK1MPxYUVM1/CBI5oW"
    private const val BACKUP_SERVER_PUBLIC_PARAMS =
        "AZwNSU55fsFCbgaxGRD11wO1juAs8Yr5GF8FPlGzzvdJJIKH5/4CC7ZJSOe3yL2vturVaRU2Cx0n751Vt8wkj1Y4pyiScu0/S10n647ipo+iq97JZQv+UOlwH8ThyNlGT5DfxXCwTqivxHuXvZpuezPgHk5Gxl5aC6xuNxOnwmFlmu4CeSgdhW8+Pp0vAJOQ1MsU2D0+/kzI+tU94nB3tybY/Ao1AcGW2q41uKQbnOJUWwmQaFT6s+xTISgzsg7CPox6oORGX8rnyk/9lic3DbGsUHctIVpMAl/ogJBb4aYC"

    val configuration: SignalServiceConfiguration by lazy {
        val userAgent = Interceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
        }
        val decode = { text: String -> Base64.getDecoder().decode(text) }
        SignalServiceConfiguration(
            arrayOf(SignalServiceUrl("https://chat.signal.org", trustStore)),
            mapOf(
                0 to arrayOf(SignalCdnUrl("https://cdn.signal.org", trustStore)),
                2 to arrayOf(SignalCdnUrl("https://cdn2.signal.org", trustStore)),
                3 to arrayOf(SignalCdnUrl("https://cdn3.signal.org", trustStore)),
            ),
            arrayOf(SignalStorageUrl("https://storage.signal.org", trustStore)),
            arrayOf(SignalCdsiUrl("https://cdsi.signal.org", trustStore)),
            arrayOf(SignalSvr2Url("https://svr2.signal.org", trustStore, null, null)),
            listOf(userAgent),
            Optional.empty<Dns>(),
            Optional.empty<SignalProxy>(),
            decode(ZK_GROUP_SERVER_PUBLIC_PARAMS),
            decode(GENERIC_SERVER_PUBLIC_PARAMS),
            decode(BACKUP_SERVER_PUBLIC_PARAMS),
            false,
        )
    }
}
