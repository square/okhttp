package com.squareup.okhttp.guide;

import com.squareup.okhttp.OkHttpClient;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class PostExample {
  OkHttpClient client = new OkHttpClient();

  void run() throws IOException {
    byte[] body = bowlingJson("Jesse", "Jake").getBytes("UTF-8");
    String result = post(new URL("http://www.roundsapp.com/post"), body);
    System.out.println(result);
  }

  String post(URL url, byte[] body) throws IOException {
    HttpURLConnection connection = client.open(url);
    OutputStream out = null;
    InputStream in = null;
    try {
      // Write the request.
      connection.setRequestMethod("POST");
      out = connection.getOutputStream();
      out.write(body);
      out.close();

      // Read the response.
      if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
        throw new IOException("Unexpected HTTP response: "
            + connection.getResponseCode() + " " + connection.getResponseMessage());
      }
      in = connection.getInputStream();
      return readFirstLine(in);
    } finally {
      // Clean up.
      if (out != null) out.close();
      if (in != null) in.close();
    }
  }

  String readFirstLine(InputStream in) throws IOException {
    BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
    return reader.readLine();
  }

  String bowlingJson(String player1, String player2) {
    return "{'winCondition':'HIGH_SCORE',"
        + "'name':'Bowling',"
        + "'round':4,"
        + "'lastSaved':1367702411696,"
        + "'dateStarted':1367702378785,"
        + "'players':["
        + "{'name':'" + player1 + "','history':[10,8,6,7,8],'color':-13388315,'total':39},"
        + "{'name':'" + player2 + "','history':[6,10,5,10,10],'color':-48060,'total':41}"
        + "]}";
  }

  public static void main(String[] args) throws IOException {
    new PostExample().run();
  }
}
