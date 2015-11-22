OkHttp
======

An HTTP & SPDY client for Android and Java applications. For more information see [the website][1] and [the wiki][2].

Download
--------

Download [the latest JAR][3] or grab via Maven:
```xml
<dependency>
  <groupId>com.squareup.okhttp</groupId>
  <artifactId>okhttp</artifactId>
  <version>2.6.0</version>
</dependency>
```
or Gradle:
```groovy
compile 'com.squareup.okhttp:okhttp:2.6.0'
```

Snapshots of the development version are available in [Sonatype's `snapshots` repository][snap].


MockWebServer
-------------

A library for testing HTTP, HTTPS, HTTP/2.0, and SPDY clients.

MockWebServer coupling with OkHttp is essential for proper testing of SPDY and HTTP/2.0 so that code can be shared.

### Download

Download [the latest JAR][4] or grab via Maven:
```xml
<dependency>
  <groupId>com.squareup.okhttp</groupId>
  <artifactId>mockwebserver</artifactId>
  <version>2.6.0</version>
  <scope>test</scope>
</dependency>
```
or Gradle:
```groovy
testCompile 'com.squareup.okhttp:mockwebserver:2.6.0'
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


 [1]: http://square.github.io/okhttp
 [2]: https://github.com/square/okhttp/wiki
 [3]: https://search.maven.org/remote_content?g=com.squareup.okhttp&a=okhttp&v=LATEST
 [4]: https://search.maven.org/remote_content?g=com.squareup.okhttp&a=mockwebserver&v=LATEST
 [snap]: https://oss.sonatype.org/content/repositories/snapshots/
