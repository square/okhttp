OkHttp Coroutines
==========

Komfortable APIs for Kotlin clients.

```kotlin
val call = client.newCall(request)

call.executeAsync().use {
  withContext(Dispatchers.IO) {
    println(response.body?.string())
  }
}
```
