package okhttp3.issues

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class IssueTemplate : IssueBase() {
  @Before
  fun setup() {
    enableTls()

    clientTestRule.recordEvents = true
  }

  @Test
  fun testPostRequest() {
    server.enqueue(
        MockResponse().setResponseCode(200).setBody("Hello World!"))

    val request = Request.Builder()
        .url(server.url("/"))
        .method("POST", "BODY".toRequestBody("text/plain".toMediaType()))
        .build()
    client.newCall(request).execute().use {
      assertEquals("Hello World!", it.body!!.string())
    }
  }
}
