package com.squareup.okhttp.sample;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.squareup.okhttp.OkHttpClient;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSession;

public class OkHttpContributors {
  private static final String ENDPOINT = "https://api.github.com/repos/square/okhttp/contributors";
  private static final Gson GSON = new Gson();
  private static final TypeToken<List<Contributor>> CONTRIBUTORS =
      new TypeToken<List<Contributor>>() {
      };

  class Contributor {
    String login;
    int contributions;
  }

  public static void main(String... args) throws Exception {
    OkHttpClient client = new OkHttpClient();

    // Ignore invalid SSL endpoints.
    client.setHostnameVerifier(new HostnameVerifier() {
      @Override public boolean verify(String s, SSLSession sslSession) {
        return true;
      }
    });

    // Create request for remote resource.
    HttpURLConnection connection = client.open(new URL(ENDPOINT));
    InputStream is = connection.getInputStream();
    InputStreamReader isr = new InputStreamReader(is);

    // Deserialize HTTP response to concrete type.
    List<Contributor> contributors = GSON.fromJson(isr, CONTRIBUTORS.getType());

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
