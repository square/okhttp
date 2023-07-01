package okhttp3

import assertk.assertThat
import assertk.assertions.hasSize
import io.helidon.common.http.Http
import io.helidon.logging.common.LogConfig
import io.helidon.nima.webserver.WebServer
import io.helidon.nima.webserver.http.HttpRouting
import io.helidon.nima.webserver.http.ServerRequest
import io.helidon.nima.webserver.http.ServerResponse
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketPolicy
import mockwebserver3.junit5.internal.MockWebServerExtension
import okhttp3.internal.http2.Http2
import org.assertj.core.api.Assertions
import org.junit.Before
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.RegisterExtension

@ExtendWith(MockWebServerExtension::class)
class Repro7913 {
    @RegisterExtension
    val clientTestRule: OkHttpClientTestRule = OkHttpClientTestRule()

    @RegisterExtension
    val testLogHandler = TestLogHandler(Http2::class.java)

    private lateinit var server: MockWebServer

    val client = clientTestRule.newClientBuilder()
        .protocols(listOf(Protocol.H2_PRIOR_KNOWLEDGE))
        .build()

    @BeforeEach
    fun setup(server: MockWebServer) {
        this.server = server
        server.protocols = listOf(Protocol.H2_PRIOR_KNOWLEDGE)
    }

    @Test
    fun testResets() {
        val server = WebServer.builder()
            .host("localhost")
            .port(8080)
            .addRouting {
                HttpRouting.builder()
                    .route(Http.Method.GET, "/test") { req: ServerRequest, res: ServerResponse ->
                        // Force timeout
                        Thread.sleep(10000)
                        res.send("OK")
                    }
                    .build()
            }
            .build()
        server.start()
        val builder = Request.Builder().url("http://localhost:" + server.port() + "/test")
        try {
            client
                .newCall(builder.get().build())
                .execute().use { postBookRes ->
                    assert(postBookRes.code == 200)
                    assert(postBookRes.body.string() == "OK")
                    assert(postBookRes.protocol === Protocol.HTTP_2)
                }
        } finally {
            server.stop()

            assertThat(testLogHandler.takeAll().filter { it.contains("RST_STREAM") }).hasSize(1)
        }
    }

    @Test
    fun testResetMockWebServer() {
        server.enqueue(MockResponse(socketPolicy = SocketPolicy.NoResponse))

        val builder = Request.Builder().url("http://localhost:" + server.port + "/test")
        try {
            client
                .newCall(builder.get().build())
                .execute().use { postBookRes ->
                    assert(postBookRes.code == 200)
                    assert(postBookRes.body.string() == "OK")
                    assert(postBookRes.protocol === Protocol.HTTP_2)
                }
        } finally {
            assertThat(testLogHandler.takeAll().filter { it.contains("RST_STREAM") }).hasSize(1)
        }
    }
}
