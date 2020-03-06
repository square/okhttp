package okhttp3.android

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.internal.platform.Platform
import okhttp3.internal.platform.android.BuildConfig

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

    companion object {
        @JvmOverloads
        fun build(appCtxt: Context, buildConfigClass: Class<*>, init: OkHttpClient.Builder.() -> Unit = {}): OkHttpAndroidClientFactory {
            check(Platform.get().isAndroid) {
                "OkHttpAndroidClientFactory is only for Android"
            }

            val buildConfig = BuildConfig(buildConfigClass)

            Platform.get().isDevelopmentMode = buildConfig.DEBUG

            val builder = OkHttpClient.Builder()
            init(builder)
            val client = builder.build()

            return OkHttpAndroidClientFactory(client)
        }
    }
}
