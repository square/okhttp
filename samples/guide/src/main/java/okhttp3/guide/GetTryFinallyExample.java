package okhttp3.guide;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.internal.Util;

import java.io.IOException;

/**
 * Example using try/finally (typically when try with resources is not available).
 */
public class GetTryFinallyExample {

  OkHttpClient client = new OkHttpClient();

  String run(String url) throws IOException {
    Request request = new Request.Builder()
        .url(url)
        .build();

    Response response = null;
    try {
      response = client.newCall(request).execute();
      return response.body().string();
    } finally {
      // ensure underlying response resources are closed
      Util.closeQuietly(response);
    }
  }

  public static void main(String[] args) throws IOException {
    GetTryFinallyExample example = new GetTryFinallyExample();
    String response = example.run("https://raw.github.com/square/okhttp/master/README.md");
    System.out.println(response);
  }
}
