package okhttp3.x;

import okhttp3.*;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.Rule;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;

public class FailingOkHttp {
    @Rule public final MockWebServer server = new MockWebServer();

    @Test
    public void test() throws Exception {
        server.enqueue(new MockResponse()
                           .addHeader("Content-Type: text/plain")
                           .setBody("abc"));

        File file = Files.createTempDirectory("deviation-service-cache").toFile();
        long cacheSize = 10 * 1024 * 1024; // 10 Mb

      System.out.println(System.getProperty("java.version"));

        OkHttpClient okHttpClient = new OkHttpClient.Builder()
            .cache(new Cache(file, cacheSize))
            .build();


        Request request = new Request.Builder()
            .url(server.url("/path"))
            .header("User-Agent", "Test Case")
            .build();

        Call call = okHttpClient.newCall(request);

        Response response = call.execute();
        assertEquals("text/plain", response.header("Content-Type"));
        assertEquals("abc", response.body().string());
    }
}
