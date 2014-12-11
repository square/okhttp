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

import com.squareup.okhttp.Cache;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
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
  private final OkHttpClient client;
  private final Set<URL> fetchedUrls = Collections.synchronizedSet(new LinkedHashSet<URL>());
  private final LinkedBlockingQueue<URL> queue = new LinkedBlockingQueue<>();

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
    Request request = new Request.Builder()
        .url(url)
        .build();
    Response response = client.newCall(request).execute();
    String responseSource = response.networkResponse() != null
        ? ("(network: " + response.networkResponse().code() + ")")
        : "(cache)";
    int responseCode = response.code();

    System.out.printf("%03d: %s %s%n", responseCode, url, responseSource);

    String contentType = response.header("Content-Type");
    if (responseCode != 200 || contentType == null) {
      response.body().close();
      return;
    }

    Document document = Jsoup.parse(response.body().string(), url.toString());
    for (Element element : document.select("a[href]")) {
      String href = element.attr("href");
      URL link = parseUrl(url, href);
      if (link != null) queue.add(link);
    }
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
    Cache cache = new Cache(new File(args[0]), cacheByteCount);
    client.setCache(cache);

    Crawler crawler = new Crawler(client);
    crawler.queue.add(new URL(args[1]));
    crawler.parallelDrainQueue(threadCount);
  }
}
