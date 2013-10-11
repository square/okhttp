OkHttp
======

An HTTP & SPDY client for Android and Java applications.

For more information please see [the website][1].



Download
--------

Download [the latest JAR][2] or grab via Maven:

```xml
<dependency>
    <groupId>com.squareup.okhttp</groupId>
    <artifactId>okhttp</artifactId>
    <version>(insert latest version)</version>
</dependency>
```


Building
--------

### On the Desktop

Run OkHttp tests on the desktop with Maven. Running SPDY tests on the desktop uses
[Jetty-NPN][3] which requires OpenJDK 7+.

```
mvn clean test
```

### On a Device

OkHttp's test suite creates an in-process HTTPS server. Prior to Android 2.3, SSL server sockets
were broken, and so HTTPS tests will time out when run on such devices.

Test on a USB-attached Android using [Vogar][4]. Unfortunately `dx` requires that you build with
Java 6, otherwise the test class will be silently omitted from the `.dex` file.

```
mvn clean
mvn package -DskipTests
vogar \
    --classpath ~/.m2/repository/org/bouncycastle/bcprov-jdk15on/1.47/bcprov-jdk15on-1.47.jar \
    --classpath ~/.m2/repository/com/google/mockwebserver/mockwebserver/20130122/mockwebserver-20130122.jar \
    --classpath target/okhttp-0.9-SNAPSHOT.jar \
    ./src/test/java
```

MockWebServer
-------------

A library for testing HTTP, HTTPS, HTTP/2.0, and SPDY clients.

MockWebServer coupling with OkHttp is essential for proper testing of SPDY and HTTP/2.0 so that code can be shared.

### Download

Download [the latest JAR][5] or grab via Maven:

```xml
<dependency>
    <groupId>com.squareup.okhttp</groupId>
    <artifactId>mockwebserver</artifactId>
    <version>(insert latest version)</version>
    <scope>test</scope>
</dependency>
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
 [2]: http://repository.sonatype.org/service/local/artifact/maven/redirect?r=central-proxy&g=com.squareup.okhttp&a=okhttp&v=LATEST
 [3]: http://wiki.eclipse.org/Jetty/Feature/NPN
 [4]: https://code.google.com/p/vogar/
 [5]: http://repository.sonatype.org/service/local/artifact/maven/redirect?r=central-proxy&g=com.squareup.okhttp&a=mockwebserver&v=LATEST
