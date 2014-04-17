package com.squareup.okhttp.sample;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;
import java.io.Reader;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class OkHttpContributors {
  private static final String ENDPOINT = "https://api.github.com/repos/square/okhttp/contributors";
  private static final Gson GSON = new Gson();
  private static final TypeToken<List<Contributor>> CONTRIBUTORS =
      new TypeToken<List<Contributor>>() {
      };

  static class Contributor {
    String login;
    int contributions;
  }

  public static void main(String... args) throws Exception {
    OkHttpClient client = new OkHttpClient();

    // Create request for remote resource.
    Request request = new Request.Builder()
        .url(ENDPOINT)
        .build();

    // Execute the request and retrieve the response.
    Response response = client.execute(request);

    // Deserialize HTTP response to concrete type.
    Reader body = response.body().charStream();
    List<Contributor> contributors = GSON.fromJson(body, CONTRIBUTORS.getType());

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

  private OkHttpContributors() {
    // No instances.
  }
}
