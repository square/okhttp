package okhttp3.sample;


import java.io.Reader;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class OkHttpContributorsFastjson {
  private static final String ENDPOINT = "https://api.github.com/repos/square/okhttp/contributors";
  private static final TypeReference<List<Contributor>> CONTRIBUTORS =
      new TypeReference<List<Contributor>>() {
      };

  public static class Contributor {
    public String login;
    public int contributions;
  }

  public static void main(String... args) throws Exception {
    OkHttpClient client = new OkHttpClient();

    // Create request for remote resource.
    Request request = new Request.Builder()
        .url(ENDPOINT)
        .build();

    // Execute the request and retrieve the response.
    Response response = client.newCall(request).execute();

    // Deserialize HTTP response to concrete type.
    ResponseBody body = response.body();
    List<Contributor> contributors = JSON.parseObject(body.string(), CONTRIBUTORS.getType());
    body.close();

    // Sort list by the most contributions.
    Collections.sort(contributors, new Comparator<Contributor>() {
      @Override public int compare(Contributor c1, Contributor c2) {
        return c2.contributions - c1.contributions;
      }
    });

    // Output list of contributors.
    for (Contributor contributor : contributors) {
      System.out.println(contributor.login + ": " + contributor.contributions);
    }
  }

  private OkHttpContributorsFastjson() {
    // No instances.
  }
}
