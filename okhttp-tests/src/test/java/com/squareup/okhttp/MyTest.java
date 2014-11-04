package com.squareup.okhttp;

import org.junit.Test;

/**
 * Created by Siniša on 1.11.2014..
 */
public class MyTest {

    @Test
    public void myTest() throws InterruptedException {

//        new Thread(new Runnable() {
//            @Override
//            public void run() {
//            }
//        }).start();


        OkHttpClient client = new OkHttpClient();

        Request request = new Request.Builder()
                .url("http://www.google.com")
                .build();

        Call call = client.newCall(request);
        call.enqueue(new Callback() {
            @Override
            public void onFailure(Request request, Exception e) {
                System.out.println("error > " + e.getMessage());
            }

            @Override
            public void onResponse(Response response) throws Exception {
                Thread.sleep(2000);
                System.out.println(response.body().string());
            }
        });

Thread.sleep(1000);
//        call.cancel();


        Thread.sleep(10000);

    }

}
