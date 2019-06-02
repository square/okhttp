Upgrading to OkHttp 4
=====================

SAM Conversions
---------------

When you use Java APIs from Kotlin you can operate on Java interfaces as if they were Kotlin
lambdas. The [feature][kotlin_sam] is available for interfaces that define a Single Abstract Method
(SAM).

But when you use Kotlin APIs from Kotlin there's no automatic conversion. Code that used SAM lambdas
with OkHttp 3 must use objects with OkHttp 4.

Kotlin + OkHttp 3.x:

```
val client = OkHttpClient.Builder()
    .dns { hostname -> InetAddress.getAllByName(hostname).toList() }
    .build()
```

Kotlin + OkHttp 4.x

```
val client = OkHttpClient.Builder()
    .dns(object : Dns {
      override fun lookup(hostname: String) =
          InetAddress.getAllByName(hostname).toList()
    })
    .build()
```

SAM conversion impacts these APIs:

 * `Authenticator`
 * `Dispatcher.setIdleCallback(Runnable)`
 * `Dns`
 * `EventListener.Factory`
 * `HttpLoggingInterceptor.Logger`
 * `LoggingEventListener.Factory`
 * `OkHttpClient.Builder.eventListenerFactory(EventListener.Factory)`
 * `OkHttpClient.Builder.hostnameVerifier(HostnameVerifier)`

[kotlin_sam]: https://kotlinlang.org/docs/reference/java-interop.html#sam-conversions

