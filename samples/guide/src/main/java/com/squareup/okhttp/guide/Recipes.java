package com.squareup.okhttp.guide;

import com.squareup.okhttp.Callback;
import com.squareup.okhttp.Headers;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;
import java.io.IOException;

public final class Recipes {
  public void getResponseSynchronously() throws Exception {
    OkHttpClient client = new OkHttpClient();
    Request request = new Request.Builder()
        .url("http://publicobject.com/helloworld.txt")
        .build();

    Response response = client.newCall(request).execute();
    if (response.code() < 200 || response.code() >= 300) {
      throw new IOException("Unexpected code " + response);
    }

    Headers responseHeaders = response.headers();
    for (int i = 0; i < responseHeaders.size(); i++) {
      System.out.println(responseHeaders.name(i) + ": " + responseHeaders.value(i));
    }

    System.out.println(response.body().string());
  }

  public void getResponseAsynchronously() throws Exception {
    OkHttpClient client = new OkHttpClient();
    Request request = new Request.Builder()
        .url("http://publicobject.com/helloworld.txt")
        .build();

    client.newCall(request).enqueue(new Callback() {
      @Override public void onFailure(Request request, Throwable throwable) {
        throwable.printStackTrace();
      }

      @Override public void onResponse(Response response) throws IOException {
        if (response.code() < 200 || response.code() >= 300) {
          throw new IOException("Unexpected code " + response);
        }

        Headers responseHeaders = response.headers();
        for (int i = 0; i < responseHeaders.size(); i++) {
          System.out.println(responseHeaders.name(i) + ": " + responseHeaders.value(i));
        }

        System.out.println(response.body().string());
      }
    });
  }

  public static void main(String[] args) throws Exception {
    Recipes recipes = new Recipes();
    recipes.getResponseSynchronously();
    recipes.getResponseAsynchronously();
  }
}
