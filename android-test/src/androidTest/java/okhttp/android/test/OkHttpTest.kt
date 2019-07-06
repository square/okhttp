package okhttp.android.test

import android.os.Build
import android.support.test.runner.AndroidJUnit4
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.TlsVersion
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Run with "./gradlew :android-test:connectedCheck" and make sure ANDROID_SDK_ROOT is set.
 */
@RunWith(AndroidJUnit4::class)
class OkHttpTest {
  private lateinit var client: OkHttpClient

  @Before
  fun createClient() {
    client = OkHttpClient.Builder()
        .build()
  }

  @After
  fun cleanup() {
    client.dispatcher.executorService.shutdownNow()
  }

  @Test
  fun testRequest() {
    val request = Request.Builder().url("https://api.twitter.com/robots.txt").build()

    val response = client.newCall(request).execute()

    response.use {
      assertEquals(200, response.code)
    }
  }

  @Test
  fun testRequestUsesAndroidConscrypt() {
    val request = Request.Builder().url("https://facebook.com/robots.txt").build()

    var socketClass: String? = null

    val client2 = client.newBuilder()
        .eventListener(object : EventListener() {
          override fun connectionAcquired(call: Call, connection: Connection) {
            socketClass = connection.socket().javaClass.name
          }
        })
        .build()

    val response = client2.newCall(request).execute()

    response.use {
      assertEquals(Protocol.HTTP_2, response.protocol)
      if (Build.VERSION.SDK_INT >= 29) {
        assertEquals(TlsVersion.TLS_1_3, response.handshake?.tlsVersion)
      } else {
        assertEquals(TlsVersion.TLS_1_2, response.handshake?.tlsVersion)
      }
      assertEquals(200, response.code)
      assertEquals("com.android.org.conscrypt.Java8FileDescriptorSocket", socketClass)
    }
  }

  @Test
  fun testHttpRequestBlocked() {
    val request = Request.Builder().url("http://api.twitter.com/robots.txt").build()

    try {
      client.newCall(request).execute()
      fail("expected cleartext blocking")
    } catch (_: java.net.UnknownServiceException) {
    }
  }
}
