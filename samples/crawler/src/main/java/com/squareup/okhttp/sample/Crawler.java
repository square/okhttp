/*
 * Copyright (C) 2014 Square, Inc.
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
package com.squareup.okhttp.sample;

import com.squareup.okhttp.HttpResponseCache;
import com.squareup.okhttp.MediaType;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.internal.http.OkHeaders;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * Fetches HTML from a requested URL, follows the links, and repeats.
 */
public final class Crawler {
  public static final Charset UTF_8 = Charset.forName("UTF-8");

  private final OkHttpClient client;
  private final Set<URL> fetchedUrls = Collections.synchronizedSet(new LinkedHashSet<URL>());
  private final LinkedBlockingQueue<URL> queue = new LinkedBlockingQueue<URL>();

  public Crawler(OkHttpClient client) {
    this.client = client;
  }

  private void parallelDrainQueue(int threadCount) {
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    for (int i = 0; i < threadCount; i++) {
      executor.execute(new Runnable() {
        @Override public void run() {
          try {
            drainQueue();
          } catch (Exception e) {
            e.printStackTrace();
          }
        }
      });
    }
    executor.shutdown();
  }

  private void drainQueue() throws Exception {
    for (URL url; (url = queue.take()) != null; ) {
      if (!fetchedUrls.add(url)) {
        continue;
      }

      try {
        fetch(url);
      } catch (IOException e) {
        System.out.printf("XXX: %s %s%n", url, e);
      }
    }
  }

  public void fetch(URL url) throws IOException {
    HttpURLConnection connection = client.open(url);
    String responseSource = connection.getHeaderField(OkHeaders.RESPONSE_SOURCE);
    String contentType = connection.getHeaderField("Content-Type");
    int responseCode = connection.getResponseCode();

    System.out.printf("%03d: %s %s%n", responseCode, url, responseSource);

    if (responseCode >= 400) {
      connection.getErrorStream().close();
      return;
    }

    InputStream in = connection.getInputStream();
    if (responseCode != 200 || contentType == null) {
      in.close();
      return;
    }

    MediaType mediaType = MediaType.parse(contentType);
    Document document = Jsoup.parse(in, mediaType.charset(UTF_8).name(), url.toString());
    for (Element element : document.select("a[href]")) {
      String href = element.attr("href");
      URL link = parseUrl(url, href);
      if (link != null) queue.add(link);
    }

    in.close();
  }

  private URL parseUrl(URL url, String href) {
    try {
      URL result = new URL(url, href);
      return result.getProtocol().equals("http") || result.getProtocol().equals("https")
          ? result
          : null;
    } catch (MalformedURLException e) {
      return null;
    }
  }

  public static void main(String[] args) throws IOException {
    if (args.length != 2) {
      System.out.println("Usage: Crawler <cache dir> <root>");
      return;
    }

    int threadCount = 20;
    long cacheByteCount = 1024L * 1024L * 100L;

    OkHttpClient client = new OkHttpClient();
    HttpResponseCache httpResponseCache = new HttpResponseCache(new File(args[0]), cacheByteCount);
    client.setOkResponseCache(httpResponseCache);

    Crawler crawler = new Crawler(client);
    crawler.queue.add(new URL(args[1]));
    crawler.parallelDrainQueue(threadCount);
  }
}
