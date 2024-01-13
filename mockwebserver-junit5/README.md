MockWebServer for JUnit 5
=========================

This module integrates mockwebserver3.MockWebServer with JUnit 5.

To use, first add this library as a test dependency:

```
testRuntimeOnly("com.squareup.okhttp3:mockwebserver3-junit5:4.12.0")
```

Then in tests annotated `@org.junit.jupiter.api.Test`, you may add a [MockWebServer] as a test
method parameter. It will be shut down automatically after the test runs.

```
class MyTest {
  @Test
  void test(MockWebServer server) {
    ...
  }
}
```

Alternately you may add the [MockWebServer] as a constructor parameter:

```
class MyTest {
  private final MockWebServer server;

  MyTest(MockWebServer server) {
    this.server = server;
  }

  @Test
  void test() {
    ...
  }
}
```

Constructor injection is particularly concise in Kotlin:

```
class MyTest(
  private val server: MockWebServer
) {
  @Test
  fun test() {
    ...
  }
}
```

Multiple instances can be obtained by naming additional ones:

```
class MyTest(
  private val server: MockWebServer,
  @MockWebServerInstance("server2") private val server2: MockWebServer,
  @MockWebServerInstance("server3") private val server3: MockWebServer
) {
  @Test
  fun test() {
    ...
  }
}
```

Requirements
------------

MockWebServer's JUnit 5 integration works on Android 7.0+ (API level 24+) and Java 8+. Note that
this is above OkHttp's core requirements.

