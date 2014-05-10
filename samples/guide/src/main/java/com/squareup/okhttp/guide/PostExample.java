package com.squareup.okhttp.guide;

import com.squareup.okhttp.MediaType;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.RequestBody;
import com.squareup.okhttp.Response;
import java.io.IOException;

public class PostExample {
  OkHttpClient client = new OkHttpClient();

  void run() throws IOException {
    String json = bowlingJson("Jesse", "Jake");
    RequestBody body = RequestBody.create(MediaType.parse("application/json"), json);
    Request request = new Request.Builder().url("http://www.roundsapp.com/post").post(body).build();

    Response response = client.newCall(request).execute();
    System.out.println(response.body().string());
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
