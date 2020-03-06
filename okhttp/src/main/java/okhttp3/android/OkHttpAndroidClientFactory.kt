package okhttp3.android

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.internal.platform.Platform
import okhttp3.internal.platform.android.BuildConfig
import okhttp3.internal.tls.AllowlistedTrustManager

class OkHttpAndroidClientFactory private constructor(val client: OkHttpClient) {
    fun newClient(init: OkHttpClient.Builder.() -> Unit): OkHttpClient {
        val builder = client.newBuilder()
        init(builder)
        return builder.build()
    }

    fun shutdown() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    class OkHttpAndroidClientFactoryInit(private val clientBuilder: OkHttpClient.Builder) {
        /**
         * Consider using https://developer.android.com/training/articles/security-config#TrustingDebugCa
         * instead?
         */
        fun enableDevWhitelist(vararg hosts: String) = apply {
            check(Platform.get().isDevelopmentMode) {
                "Not allowed for production builds"
            }

            val tm = Platform.get().platformTrustManager()
            val trustManager = AllowlistedTrustManager(tm, *hosts)
            val sf = Platform.get().newSSLContext().apply {
                init(null, arrayOf(trustManager), null)
            }.socketFactory
            clientBuilder.sslSocketFactory(sf, trustManager)
        }

        fun client(init: OkHttpClient.Builder.() -> Unit = {}) {
            init(clientBuilder)
        }
    }

    companion object {
        @JvmOverloads
        fun build(appCtxt: Context, buildConfigClass: Class<*>, init: OkHttpAndroidClientFactoryInit.() -> Unit = {}): OkHttpAndroidClientFactory {
            check(Platform.get().isAndroid) {
                "OkHttpAndroidClientFactory is only for Android"
            }

            val buildConfig = BuildConfig(buildConfigClass)

            Platform.get().isDevelopmentMode = buildConfig.DEBUG

            val builder = OkHttpClient.Builder()
            init(OkHttpAndroidClientFactoryInit(builder))
            val client = builder.build()

            return OkHttpAndroidClientFactory(client)
        }
    }
}
