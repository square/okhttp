OkHttp
======

An HTTP+SPDY client for Android and Java applications.


Download
--------

Downloadable .jars can be found on the [GitHub download page][1].

You can also depend on the .jar through Maven:

```xml
<dependency>
    <groupId>com.squareup</groupId>
    <artifactId>okhttp</artifactId>
    <version>(insert latest version)</version>
</dependency>
```


Known Issues
------------

The SPDY implementation is incomplete:

* Settings frames are not honored. Flow control is not implemented.
* It assumes a well-behaved peer. If the peer sends an invalid frame, OkHttp's SPDY client will not respond with the required `RST` frame.

OkHttp uses the platform's [ProxySelector][2]. Prior to Android 4.0, `ProxySelector` didn't honor the `proxyHost` and `proxyPort` system properties for HTTPS connections. Work around this by specifying the `https.proxyHost` and `https.proxyPort` system properties when using a proxy with HTTPS.

OkHttp's test suite creates an in-process HTTPS server. Prior to Android 2.3, SSL server sockets were broken, and so HTTPS tests will time out when run on such devices.


Contributing
------------

If you would like to contribute code to OkHttp you can do so through GitHub by
forking the repository and sending a pull request.

When submitting code, please make every effort to follow existing conventions
and style in order to keep the code as readable as possible. Please also make
sure your code compiles by running `mvn clean verify`. Checkstyle failures
during compilation indicate errors in your style and can be viewed in the
`checkstyle-result.xml` file.

Before your code can be accepted into the project you must also sign the
[Individual Contributor License Agreement (CLA)][3].


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



 [1]: http://github.com/square/okhttp/downloads
 [2]: http://developer.android.com/reference/java/net/ProxySelector.html
 [3]: https://spreadsheets.google.com/spreadsheet/viewform?formkey=dDViT2xzUHAwRkI3X3k5Z0lQM091OGc6MQ&ndplr=1
