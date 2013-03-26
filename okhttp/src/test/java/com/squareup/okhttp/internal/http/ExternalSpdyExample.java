/*
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.squareup.okhttp.internal.http;

import com.squareup.okhttp.OkHttpClient;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSession;

public final class ExternalSpdyExample {
  public static void main(String[] args) throws Exception {
    URL url = new URL("https://www.google.ca/");
    HttpsURLConnection connection = (HttpsURLConnection) new OkHttpClient().open(url);

    connection.setHostnameVerifier(new HostnameVerifier() {
      @Override public boolean verify(String s, SSLSession sslSession) {
        System.out.println("VERIFYING " + s);
        return true;
      }
    });

    int responseCode = connection.getResponseCode();
    System.out.println(responseCode);

    BufferedReader reader =
        new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"));
    String line;
    while ((line = reader.readLine()) != null) {
      System.out.println(line);
    }
  }
}
