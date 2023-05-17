@file:Suppress("NewApi")

package okhttp3.tls

import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.nio.charset.StandardCharsets


suspend fun main() {
    val url = "https://httpbin.org/get"

    val client = OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .build()

    val username = "TESTÑ"
    println(username)
//    val s = "TEST\u00c3\u0091"
//    println(s)
    val s = "TEST\u00d1"
    println(s)

    val headers = Headers.Builder()
        .addUnsafeNonAscii("X-unicode", username)
        .unsafeEncoding(StandardCharsets.ISO_8859_1)
        .build()
    val call = client.newCall(
        Request(
            url = url.toHttpUrl(),
            headers = headers
        )
    )

    val response = call.execute()

    println(response.protocol)
    println("response: ${response.body.string()}")
}
