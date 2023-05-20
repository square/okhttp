package okhttp.android.test

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class CheckCerts {
  private lateinit var client: OkHttpClient

  @BeforeEach
  fun setUp() {
    client = OkHttpClient.Builder()
      .build()
  }

  @Test
  fun makeBadSslRequest() {
    val googleRequest = Request("https://self-signed.badssl.com/".toHttpUrl())

    val response = client.newCall(googleRequest).execute()

    println(response.body.string())
  }

  @Test
  fun makeGoogleRequest() {
    val googleRequest = Request("https://www.google.com/robots.txt".toHttpUrl())

    val response = client.newCall(googleRequest).execute()

    println(response.body.string())
  }
}
