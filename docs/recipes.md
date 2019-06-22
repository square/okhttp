Recipes
=======

We've written some recipes that demonstrate how to solve common problems with OkHttp. Read through them to learn about how everything works together. Cut-and-paste these examples freely; that's what they're for.

#### [Synchronous Get](https://github.com/square/okhttp/blob/master/samples/guide/src/main/java/okhttp3/recipes/SynchronousGet.java)

Download a file, print its headers, and print its response body as a string.

The `string()` method on response body is convenient and efficient for small documents. But if the response body is large (greater than 1 MiB), avoid `string()` because it will load the entire document into memory. In that case, prefer to process the body as a stream.

```java
  private final OkHttpClient client = new OkHttpClient();

  public void run() throws Exception {
    Request request = new Request.Builder()
        .url("https://publicobject.com/helloworld.txt")
        .build();

    try (Response response = client.newCall(request).execute()) {
      if (!response.isSuccessful()) throw new IOException("Unexpected code " + response);

      Headers responseHeaders = response.headers();
      for (int i = 0; i < responseHeaders.size(); i++) {
        System.out.println(responseHeaders.name(i) + ": " + responseHeaders.value(i));
      }

      System.out.println(response.body().string());
    }
  }
```

#### [Asynchronous Get](https://github.com/square/okhttp/blob/master/samples/guide/src/main/java/okhttp3/recipes/AsynchronousGet.java)

Download a file on a worker thread, and get called back when the response is readable. The callback is made after the response headers are ready. Reading the response body may still block. OkHttp doesn't currently offer asynchronous APIs to receive a response body in parts.

```java
  private final OkHttpClient client = new OkHttpClient();

  public void run() throws Exception {
    Request request = new Request.Builder()
        .url("http://publicobject.com/helloworld.txt")
        .build();

    client.newCall(request).enqueue(new Callback() {
      @Override public void onFailure(Call call, IOException e) {
        e.printStackTrace();
      }

      @Override public void onResponse(Call call, Response response) throws IOException {
        try (ResponseBody responseBody = response.body()) {
          if (!response.isSuccessful()) throw new IOException("Unexpected code " + response);

          Headers responseHeaders = response.headers();
          for (int i = 0, size = responseHeaders.size(); i < size; i++) {
            System.out.println(responseHeaders.name(i) + ": " + responseHeaders.value(i));
          }

          System.out.println(responseBody.string());
        }
      }
    });
  }
```

#### [Accessing Headers](https://github.com/square/okhttp/blob/master/samples/guide/src/main/java/okhttp3/recipes/AccessHeaders.java)

Typically HTTP headers work like a `Map<String, String>`: each field has one value or none. But some headers permit multiple values, like Guava's [Multimap](http://docs.guava-libraries.googlecode.com/git/javadoc/com/google/common/collect/Multimap.html). For example, it's legal and common for an HTTP response to supply multiple `Vary` headers. OkHttp's APIs attempt to make both cases comfortable.

When writing request headers, use `header(name, value)` to set the only occurrence of `name` to `value`. If there are existing values, they will be removed before the new value is added. Use `addHeader(name, value)` to add a header without removing the headers already present.

When reading response a header, use `header(name)` to return the _last_ occurrence of the named value. Usually this is also the only occurrence! If no value is present, `header(name)` will return null. To read all of a field's values as a list, use `headers(name)`.

To visit all headers, use the `Headers` class which supports access by index.

```java
  private final OkHttpClient client = new OkHttpClient();

  public void run() throws Exception {
    Request request = new Request.Builder()
        .url("https://api.github.com/repos/square/okhttp/issues")
        .header("User-Agent", "OkHttp Headers.java")
        .addHeader("Accept", "application/json; q=0.5")
        .addHeader("Accept", "application/vnd.github.v3+json")
        .build();

    try (Response response = client.newCall(request).execute()) {
      if (!response.isSuccessful()) throw new IOException("Unexpected code " + response);

      System.out.println("Server: " + response.header("Server"));
      System.out.println("Date: " + response.header("Date"));
      System.out.println("Vary: " + response.headers("Vary"));
    }
  }
```

#### [Posting a String](https://github.com/square/okhttp/blob/master/samples/guide/src/main/java/okhttp3/recipes/PostString.java)

Use an HTTP POST to send a request body to a service. This example posts a markdown document to a web service that renders markdown as HTML. Because the entire request body is in memory simultaneously, avoid posting large (greater than 1 MiB) documents using this API.

```java
  public static final MediaType MEDIA_TYPE_MARKDOWN
      = MediaType.parse("text/x-markdown; charset=utf-8");

  private final OkHttpClient client = new OkHttpClient();

  public void run() throws Exception {
    String postBody = ""
        + "Releases\n"
        + "--------\n"
        + "\n"
        + " * _1.0_ May 6, 2013\n"
        + " * _1.1_ June 15, 2013\n"
        + " * _1.2_ August 11, 2013\n";

    Request request = new Request.Builder()
        .url("https://api.github.com/markdown/raw")
        .post(RequestBody.create(MEDIA_TYPE_MARKDOWN, postBody))
        .build();

    try (Response response = client.newCall(request).execute()) {
      if (!response.isSuccessful()) throw new IOException("Unexpected code " + response);

      System.out.println(response.body().string());
    }
  }
```

#### [Post Streaming](https://github.com/square/okhttp/blob/master/samples/guide/src/main/java/okhttp3/recipes/PostStreaming.java)

Here we `POST` a request body as a stream. The content of this request body is being generated as it's being written. This example streams directly into the [Okio](https://github.com/square/okio) buffered sink. Your programs may prefer an `OutputStream`, which you can get from `BufferedSink.outputStream()`.

```java
  public static final MediaType MEDIA_TYPE_MARKDOWN
      = MediaType.parse("text/x-markdown; charset=utf-8");

  private final OkHttpClient client = new OkHttpClient();

  public void run() throws Exception {
    RequestBody requestBody = new RequestBody() {
      @Override public MediaType contentType() {
        return MEDIA_TYPE_MARKDOWN;
      }

      @Override public void writeTo(BufferedSink sink) throws IOException {
        sink.writeUtf8("Numbers\n");
        sink.writeUtf8("-------\n");
        for (int i = 2; i <= 997; i++) {
          sink.writeUtf8(String.format(" * %s = %s\n", i, factor(i)));
        }
      }

      private String factor(int n) {
        for (int i = 2; i < n; i++) {
          int x = n / i;
          if (x * i == n) return factor(x) + " × " + i;
        }
        return Integer.toString(n);
      }
    };

    Request request = new Request.Builder()
        .url("https://api.github.com/markdown/raw")
        .post(requestBody)
        .build();

    try (Response response = client.newCall(request).execute()) {
      if (!response.isSuccessful()) throw new IOException("Unexpected code " + response);

      System.out.println(response.body().string());
    }
  }
```

#### [Posting a File](https://github.com/square/okhttp/blob/master/samples/guide/src/main/java/okhttp3/recipes/PostFile.java)

It's easy to use a file as a request body.

```java
  public static final MediaType MEDIA_TYPE_MARKDOWN
      = MediaType.parse("text/x-markdown; charset=utf-8");

  private final OkHttpClient client = new OkHttpClient();

  public void run() throws Exception {
    File file = new File("README.md");

    Request request = new Request.Builder()
        .url("https://api.github.com/markdown/raw")
        .post(RequestBody.create(MEDIA_TYPE_MARKDOWN, file))
        .build();

    try (Response response = client.newCall(request).execute()) {
      if (!response.isSuccessful()) throw new IOException("Unexpected code " + response);

      System.out.println(response.body().string());
    }
  }
```

#### [Posting form parameters](https://github.com/square/okhttp/blob/master/samples/guide/src/main/java/okhttp3/recipes/PostForm.java)

Use `FormBody.Builder` to build a request body that works like an HTML `<form>` tag. Names and values will be encoded using an HTML-compatible form URL encoding.

```java
  private final OkHttpClient client = new OkHttpClient();

  public void run() throws Exception {
    RequestBody formBody = new FormBody.Builder()
        .add("search", "Jurassic Park")
        .build();
    Request request = new Request.Builder()
        .url("https://en.wikipedia.org/w/index.php")
        .post(formBody)
        .build();

    try (Response response = client.newCall(request).execute()) {
      if (!response.isSuccessful()) throw new IOException("Unexpected code " + response);

      System.out.println(response.body().string());
    }
  }
```

#### [Posting a multipart request](https://github.com/square/okhttp/blob/master/samples/guide/src/main/java/okhttp3/recipes/PostMultipart.java)

`MultipartBody.Builder` can build sophisticated request bodies compatible with HTML file upload forms. Each part of a multipart request body is itself a request body, and can define its own headers. If present, these headers should describe the part body, such as its `Content-Disposition`. The `Content-Length` and `Content-Type` headers are added automatically if they're available.

```java
  /**
   * The imgur client ID for OkHttp recipes. If you're using imgur for anything other than running
   * these examples, please request your own client ID! https://api.imgur.com/oauth2
   */
  private static final String IMGUR_CLIENT_ID = "...";
  private static final MediaType MEDIA_TYPE_PNG = MediaType.parse("image/png");

  private final OkHttpClient client = new OkHttpClient();

  public void run() throws Exception {
    // Use the imgur image upload API as documented at https://api.imgur.com/endpoints/image
    RequestBody requestBody = new MultipartBody.Builder()
        .setType(MultipartBody.FORM)
        .addFormDataPart("title", "Square Logo")
        .addFormDataPart("image", "logo-square.png",
            RequestBody.create(MEDIA_TYPE_PNG, new File("website/static/logo-square.png")))
        .build();

    Request request = new Request.Builder()
        .header("Authorization", "Client-ID " + IMGUR_CLIENT_ID)
        .url("https://api.imgur.com/3/image")
        .post(requestBody)
        .build();

    try (Response response = client.newCall(request).execute()) {
      if (!response.isSuccessful()) throw new IOException("Unexpected code " + response);

      System.out.println(response.body().string());
    }
  }
```

#### [Parse a JSON Response With Moshi](https://github.com/square/okhttp/blob/master/samples/guide/src/main/java/okhttp3/recipes/ParseResponseWithMoshi.java)

[Moshi](https://github.com/square/moshi) is a handy API for converting between JSON and Java objects. Here we're using it to decode a JSON response from a GitHub API.

Note that `ResponseBody.charStream()` uses the `Content-Type` response header to select which charset to use when decoding the response body. It defaults to `UTF-8` if no charset is specified.

```java
  private final OkHttpClient client = new OkHttpClient();
  private final Moshi moshi = new Moshi.Builder().build();
  private final JsonAdapter<Gist> gistJsonAdapter = moshi.adapter(Gist.class);

  public void run() throws Exception {
    Request request = new Request.Builder()
        .url("https://api.github.com/gists/c2a7c39532239ff261be")
        .build();
    try (Response response = client.newCall(request).execute()) {
      if (!response.isSuccessful()) throw new IOException("Unexpected code " + response);

      Gist gist = gistJsonAdapter.fromJson(response.body().source());

      for (Map.Entry<String, GistFile> entry : gist.files.entrySet()) {
        System.out.println(entry.getKey());
        System.out.println(entry.getValue().content);
      }
    }
  }

  static class Gist {
    Map<String, GistFile> files;
  }

  static class GistFile {
    String content;
  }
```

#### [Response Caching](https://github.com/square/okhttp/blob/master/samples/guide/src/main/java/okhttp3/recipes/CacheResponse.java)

To cache responses, you'll need a cache directory that you can read and write to, and a limit on the cache's size. The cache directory should be private, and untrusted applications should not be able to read its contents!

It is an error to have multiple caches accessing the same cache directory simultaneously. Most applications should call `new OkHttpClient()` exactly once, configure it with their cache, and use that same instance everywhere. Otherwise the two cache instances will stomp on each other, corrupt the response cache, and possibly crash your program.

Response caching uses HTTP headers for all configuration. You can add request headers like `Cache-Control: max-stale=3600` and OkHttp's cache will honor them. Your webserver configures how long responses are cached with its own response headers, like `Cache-Control: max-age=9600`. There are cache headers to force a cached response, force a network response, or force the network response to be validated with a conditional GET.

```java
  private final OkHttpClient client;

  public CacheResponse(File cacheDirectory) throws Exception {
    int cacheSize = 10 * 1024 * 1024; // 10 MiB
    Cache cache = new Cache(cacheDirectory, cacheSize);

    client = new OkHttpClient.Builder()
        .cache(cache)
        .build();
  }

  public void run() throws Exception {
    Request request = new Request.Builder()
        .url("http://publicobject.com/helloworld.txt")
        .build();

    String response1Body;
    try (Response response1 = client.newCall(request).execute()) {
      if (!response1.isSuccessful()) throw new IOException("Unexpected code " + response1);

      response1Body = response1.body().string();
      System.out.println("Response 1 response:          " + response1);
      System.out.println("Response 1 cache response:    " + response1.cacheResponse());
      System.out.println("Response 1 network response:  " + response1.networkResponse());
    }

    String response2Body;
    try (Response response2 = client.newCall(request).execute()) {
      if (!response2.isSuccessful()) throw new IOException("Unexpected code " + response2);

      response2Body = response2.body().string();
      System.out.println("Response 2 response:          " + response2);
      System.out.println("Response 2 cache response:    " + response2.cacheResponse());
      System.out.println("Response 2 network response:  " + response2.networkResponse());
    }

    System.out.println("Response 2 equals Response 1? " + response1Body.equals(response2Body));
  }
```
To prevent a response from using the cache, use [`CacheControl.FORCE_NETWORK`](http://square.github.io/okhttp/4.x/okhttp/okhttp3/-cache-control/-f-o-r-c-e_-n-e-t-w-o-r-k/). To prevent it from using the network, use [`CacheControl.FORCE_CACHE`](http://square.github.io/okhttp/4.x/okhttp/okhttp3/-cache-control/-f-o-r-c-e_-c-a-c-h-e/). Be warned: if you use `FORCE_CACHE` and the response requires the network, OkHttp will return a `504 Unsatisfiable Request` response.

#### [Canceling a Call](https://github.com/square/okhttp/blob/master/samples/guide/src/main/java/okhttp3/recipes/CancelCall.java)

Use `Call.cancel()` to stop an ongoing call immediately. If a thread is currently writing a request or reading a response, it will receive an `IOException`. Use this to conserve the network when a call is no longer necessary; for example when your user navigates away from an application. Both synchronous and asynchronous calls can be canceled.

```java
  private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);
  private final OkHttpClient client = new OkHttpClient();

  public void run() throws Exception {
    Request request = new Request.Builder()
        .url("http://httpbin.org/delay/2") // This URL is served with a 2 second delay.
        .build();

    final long startNanos = System.nanoTime();
    final Call call = client.newCall(request);

    // Schedule a job to cancel the call in 1 second.
    executor.schedule(new Runnable() {
      @Override public void run() {
        System.out.printf("%.2f Canceling call.%n", (System.nanoTime() - startNanos) / 1e9f);
        call.cancel();
        System.out.printf("%.2f Canceled call.%n", (System.nanoTime() - startNanos) / 1e9f);
      }
    }, 1, TimeUnit.SECONDS);

    System.out.printf("%.2f Executing call.%n", (System.nanoTime() - startNanos) / 1e9f);
    try (Response response = call.execute()) {
      System.out.printf("%.2f Call was expected to fail, but completed: %s%n",
          (System.nanoTime() - startNanos) / 1e9f, response);
    } catch (IOException e) {
      System.out.printf("%.2f Call failed as expected: %s%n",
          (System.nanoTime() - startNanos) / 1e9f, e);
    }
  }
```

#### [Timeouts](https://github.com/square/okhttp/blob/master/samples/guide/src/main/java/okhttp3/recipes/ConfigureTimeouts.java)

Use timeouts to fail a call when its peer is unreachable. Network partitions can be due to client connectivity problems, server availability problems, or anything between. OkHttp supports connect, read, and write timeouts.

```java
  private final OkHttpClient client;

  public ConfigureTimeouts() throws Exception {
    client = new OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build();
  }

  public void run() throws Exception {
    Request request = new Request.Builder()
        .url("http://httpbin.org/delay/2") // This URL is served with a 2 second delay.
        .build();

    try (Response response = client.newCall(request).execute()) {
      System.out.println("Response completed: " + response);
    }
  }
```

#### [Per-call Configuration](https://github.com/square/okhttp/blob/master/samples/guide/src/main/java/okhttp3/recipes/PerCallSettings.java)

All the HTTP client configuration lives in `OkHttpClient` including proxy settings, timeouts, and caches. When you need to change the configuration of a single call, call `OkHttpClient.newBuilder()`. This returns a builder that shares the same connection pool, dispatcher, and configuration with the original client. In the example below, we make one request with a 500 ms timeout and another with a 3000 ms timeout.

```java
  private final OkHttpClient client = new OkHttpClient();

  public void run() throws Exception {
    Request request = new Request.Builder()
        .url("http://httpbin.org/delay/1") // This URL is served with a 1 second delay.
        .build();

    // Copy to customize OkHttp for this request.
    OkHttpClient client1 = client.newBuilder()
        .readTimeout(500, TimeUnit.MILLISECONDS)
        .build();
    try (Response response = client1.newCall(request).execute()) {
      System.out.println("Response 1 succeeded: " + response);
    } catch (IOException e) {
      System.out.println("Response 1 failed: " + e);
    }

    // Copy to customize OkHttp for this request.
    OkHttpClient client2 = client.newBuilder()
        .readTimeout(3000, TimeUnit.MILLISECONDS)
        .build();
    try (Response response = client2.newCall(request).execute()) {
      System.out.println("Response 2 succeeded: " + response);
    } catch (IOException e) {
      System.out.println("Response 2 failed: " + e);
    }
  }
```

#### [Handling authentication](https://github.com/square/okhttp/blob/master/samples/guide/src/main/java/okhttp3/recipes/Authenticate.java)

OkHttp can automatically retry unauthenticated requests. When a response is `401 Not Authorized`, an `Authenticator` is asked to supply credentials. Implementations should build a new request that includes the missing credentials. If no credentials are available, return null to skip the retry.

Use `Response.challenges()` to get the schemes and realms of any authentication challenges. When fulfilling a `Basic` challenge, use `Credentials.basic(username, password)` to encode the request header.

```java
  private final OkHttpClient client;

  public Authenticate() {
    client = new OkHttpClient.Builder()
        .authenticator(new Authenticator() {
          @Override public Request authenticate(Route route, Response response) throws IOException {
            if (response.request().header("Authorization") != null) {
              return null; // Give up, we've already attempted to authenticate.
            }

            System.out.println("Authenticating for response: " + response);
            System.out.println("Challenges: " + response.challenges());
            String credential = Credentials.basic("jesse", "password1");
            return response.request().newBuilder()
                .header("Authorization", credential)
                .build();
          }
        })
        .build();
  }

  public void run() throws Exception {
    Request request = new Request.Builder()
        .url("http://publicobject.com/secrets/hellosecret.txt")
        .build();

    try (Response response = client.newCall(request).execute()) {
      if (!response.isSuccessful()) throw new IOException("Unexpected code " + response);

      System.out.println(response.body().string());
    }
  }
```

To avoid making many retries when authentication isn't working, you can return null to give up. For example, you may want to skip the retry when these exact credentials have already been attempted:

```java
  if (credential.equals(response.request().header("Authorization"))) {
    return null; // If we already failed with these credentials, don't retry.
   }
```

You may also skip the retry when you’ve hit an application-defined attempt limit:

```java
  if (responseCount(response) >= 3) {
    return null; // If we've failed 3 times, give up.
  }
```

This above code relies on this `responseCount()` method:

```java
  private int responseCount(Response response) {
    int result = 1;
    while ((response = response.priorResponse()) != null) {
      result++;
    }
    return result;
  }
```
