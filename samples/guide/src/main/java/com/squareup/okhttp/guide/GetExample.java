package com.squareup.okhttp.guide;

import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;
import java.io.IOException;

public class GetExample {
  OkHttpClient client = new OkHttpClient();

  void run() throws IOException {
    Request request = new Request.Builder()
        .url("https://raw.github.com/square/okhttp/master/README.md")
        .build();

    Response response = client.newCall(request).execute();
    System.out.println(response.body().string());
  }

  public static void main(String[] args) throws IOException {
    new GetExample().run();
  }
}
