Logging Interceptor
===================

An [OkHttp interceptor][interceptors] which limits size of HTTP response

```java
ResponsePayloadSizeLimitInterceptor interceptor = ResponsePayloadSizeLimitInterceptor(1000000)
OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(interceptor)
                .build()
```

HTTP client in the example above will reject any response with a payload size greater than 1MB


[interceptors]: https://square.github.io/okhttp/interceptors/
