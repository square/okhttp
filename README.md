OkHttp
======

An HTTP & HTTP/2 client for Android and Java applications. For more information see [the website][1] and [the wiki][2].

Download
--------

Download [the latest JAR][3] or grab via Maven:
```xml
<dependency>
  <groupId>com.squareup.okhttp3</groupId>
  <artifactId>okhttp</artifactId>
  <version>3.10.0</version>
</dependency>
```
or Gradle:
```groovy
implementation 'com.squareup.okhttp3:okhttp:3.10.0'
```

Snapshots of the development version are available in [Sonatype's `snapshots` repository][snap].


MockWebServer
-------------

A library for testing HTTP, HTTPS, and HTTP/2 clients.

MockWebServer coupling with OkHttp is essential for proper testing of HTTP/2 so that code can be shared.

### Download

Download [the latest JAR][4] or grab via Maven:
```xml
<dependency>
  <groupId>com.squareup.okhttp3</groupId>
  <artifactId>mockwebserver</artifactId>
  <version>3.10.0</version>
  <scope>test</scope>
</dependency>
```
or Gradle:
```groovy
testImplementation 'com.squareup.okhttp3:mockwebserver:3.10.0'
```

ProGuard
--------

If you are using ProGuard you might need to add the following options:

```
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
# A resource is loaded with a relative path so the package of this class must be preserved.
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase
```

License
-------

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.


 [1]: https://square.github.io/okhttp
 [2]: https://github.com/square/okhttp/wiki
 [3]: https://search.maven.org/remote_content?g=com.squareup.okhttp3&a=okhttp&v=LATEST
 [4]: https://search.maven.org/remote_content?g=com.squareup.okhttp3&a=mockwebserver&v=LATEST
 [snap]: https://oss.sonatype.org/content/repositories/snapshots/
