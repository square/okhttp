## An HTTP & HTTP/2 client for Android and Java applications

# Overview

HTTP is the way modern applications network. It’s how we exchange data &amp; media.
Doing HTTP efficiently makes your stuff load faster and saves bandwidth.

OkHttp is an HTTP client that’s efficient by default:

- HTTP/2 support allows all requests to the same host to share a socket.
- Connection pooling reduces request latency (if HTTP/2 isn’t available).
- Transparent GZIP shrinks download sizes.
- Response caching avoids the network completely for repeat requests.


OkHttp perseveres when the network is troublesome: it will silently recover from
common connection problems. If your service has multiple IP addresses OkHttp will
attempt alternate addresses if the first connect fails. This is necessary for IPv4+IPv6
and for services hosted in redundant data centers. OkHttp supports modern TLS
features (TLS 1.3, ALPN, certificate pinning). It can be configured to fall back for
broad connectivity.

Using OkHttp is easy. Its request/response API is designed with fluent builders and
immutability. It supports both synchronous blocking calls and async calls with
callbacks.

OkHttp supports Android 5.0+ (API level 21+) and Java 8+.

# Examples

## Get a URL

This program downloads a URL and prints its contents as a string. [Full source][get_example].

```java
OkHttpClient client = new OkHttpClient();

String run(String url) throws IOException {
  Request request = new Request.Builder()
      .url(url)
      .build();

  try (Response response = client.newCall(request).execute()) {
    return response.body().string();
  }
}
```

## Post to a Server

This program posts data to a service. [Full source][post_example].

```java
public static final MediaType JSON
    = MediaType.get("application/json; charset=utf-8");

OkHttpClient client = new OkHttpClient();

String post(String url, String json) throws IOException {
  RequestBody body = RequestBody.create(JSON, json);
  Request request = new Request.Builder()
      .url(url)
      .post(body)
      .build();
  try (Response response = client.newCall(request).execute()) {
    return response.body().string();
  }
}
```

# Download

<button data-md-color-primary="teal" href="https://search.maven.org/remote_content?g=com.squareup.okhttp3&a=okhttp&v=LATEST" class="dl">&darr;&nbsp;Latest&nbsp;JAR</button>

You'll also need [Okio][okio], which OkHttp uses for fast I/O and resizable buffers. Download the
[latest JAR][download_okio]. The source code to OkHttp, its samples, and this website is [available
on GitHub][okhttp].

## Maven

```xml
<dependency>
  <groupId>com.squareup.okhttp3</groupId>
  <artifactId>okhttp</artifactId>
  <version>(insert latest version)</version>
</dependency>
```

## Gradle

```groovy
implementation 'com.squareup.okhttp3:okhttp:(insert latest version)'
```

# Contributing

If you would like to contribute code you can do so through GitHub by forking the repository and
sending a pull request. When submitting code, please make every effort to follow existing
conventions and style in order to keep the code as readable as possible. Please also make sure your
code compiles by running `/gradlew check`.

Some general advice

- Don’t change public API lightly, avoid if possible, and include your reasoning in the PR if essential. It causes pain for developers who use OkHttp and sometimes runtime errors.
- Favour a working external library if appropriate. There are many examples of OkHttp libraries that can sit on top or hook in via existing APIs.
- Get working code on a personal branch with tests before you submit a PR.
- OkHttp is a small and light dependency. Don't introduce new dependencies or major new functionality.
- OkHttp targets the intersection of RFC correct and widely implemented. Incorrect implementations that are very widely implemented e.g. a bug in Apache, Nginx, Google, Firefox should also be handled.


Before your code can be accepted into the project you must also sign the [Individual Contributor License Agreement (CLA)][cla].

# License

```
Copyright 2016 Square, Inc.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

   http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

<div class="logo">
<a href="https://squareup.com"><img src="images/logo-square.png" alt="Square, Inc."/></a>
</div>

<script type="text/javascript">
  var _gaq = _gaq || [];
  _gaq.push(['_setAccount', 'UA-40704740-2']);
  _gaq.push(['_trackPageview']);

  (function() {
    var ga = document.createElement('script'); ga.type = 'text/javascript'; ga.async = true;
    ga.src = ('https:' == document.location.protocol ? 'https://ssl' : 'http://www') + '.google-analytics.com/ga.js';
    var s = document.getElementsByTagName('script')[0]; s.parentNode.insertBefore(ga, s);
  })();
</script>

[get_example]:      https://raw.github.com/square/okhttp/master/samples/guide/src/main/java/okhttp3/guide/GetExample.java
[post_example]:     https://raw.github.com/square/okhttp/master/samples/guide/src/main/java/okhttp3/guide/PostExample.java
[download_okio]:    https://search.maven.org/remote_content?g=com.squareup.okio&a=okio&v=LATEST
[okio]:             https://github.com/square/okio
[okhttp]:           https://github.com/square/okhttp
[cla]:              https://squ.re/sign-the-cla
