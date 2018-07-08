package okhttp3.kotlin

import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

fun request(url: String? = null, init: Request.Builder.() -> Unit = {}) = Request.Builder().apply(init).build()
fun Request.rebuild(init: Request.Builder.() -> Unit = {}) = newBuilder().apply(init).build()

fun client(init: OkHttpClient.Builder.() -> Unit = {}) = OkHttpClient.Builder().apply(init).build()
fun OkHttpClient.rebuild(init: OkHttpClient.Builder.() -> Unit = {}) = newBuilder().apply(init).build()

fun url(init: HttpUrl.Builder.() -> Unit = {}) = HttpUrl.Builder().apply(init).build()
fun HttpUrl.rebuild(init: HttpUrl.Builder.() -> Unit = {}) = newBuilder().apply(init).build()