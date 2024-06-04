OkHttp Coroutines
=================

Support for Kotlin clients using coroutines.

```kotlin
val call = client.newCall(request)

call.executeAsync().use { response ->
  withContext(Dispatchers.IO) {
    println(response.body?.string())
  }
}
```

This is implemented using `suspendCancellableCoroutine`
but uses the standard Dispatcher in OkHttp. This means
that by default Kotlin's Dispatchers are not used.

Cancellation if implemented sensibly in both directions.
Cancelling a coroutine scope, will cancel the call.
Cancelling a call, will throw a CancellationException
but not cancel the scope if caught.

## Releases

The latest release is available on [Maven Central](https://central.sonatype.com/artifact/com.squareup.okhttp3/okhttp-coroutines).

```
implementation("com.squareup.okhttp3:okhttp-coroutines:5.0.0-alpha.14")
```

**Notice** that the OkHttp coroutines artifact has a different version than [OkHttp](https://github.com/square/okhttp).
