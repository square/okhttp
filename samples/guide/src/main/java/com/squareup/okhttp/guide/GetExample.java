package com.squareup.okhttp.guide;

import com.squareup.okhttp.OkHttpClient;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class GetExample {
  OkHttpClient client = new OkHttpClient();

  void run() throws IOException {
    String result = get(new URL("https://raw.github.com/square/okhttp/master/README.md"));
    System.out.println(result);
  }

  String get(URL url) throws IOException {
    HttpURLConnection connection = client.open(url);
    InputStream in = null;
    try {
      // Read the response.
      in = connection.getInputStream();
      byte[] response = readFully(in);
      return new String(response, "UTF-8");
    } finally {
      if (in != null) in.close();
    }
  }

  byte[] readFully(InputStream in) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buffer = new byte[1024];
    for (int count; (count = in.read(buffer)) != -1; ) {
      out.write(buffer, 0, count);
    }
    return out.toByteArray();
  }

  public static void main(String[] args) throws IOException {
    new GetExample().run();
  }
}
