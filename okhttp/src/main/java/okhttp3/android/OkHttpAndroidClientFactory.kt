package okhttp3.android

import android.content.Context
import okhttp3.OkHttpClient

class OkHttpAndroidClientFactory(val appCtxt: Context, val client2: OkHttpClient) {

    val client: OkHttpClient
        get() {
            return client2
        }

    fun newClient(init: OkHttpClient.Builder.() -> Unit): OkHttpClient {
        val builder = client.newBuilder()
        init(builder)
        return builder.build()
    }

    companion object {
        fun build(appCtxt: Context, init: OkHttpClient.Builder.() -> Unit): OkHttpAndroidClientFactory {
            val builder = OkHttpClient.Builder()
            init(builder)
            val client = builder.build()

            return OkHttpAndroidClientFactory(appCtxt, client)
        }
    }
}