OkHttp Web Sockets
==================

RFC6455-compliant web socket implementation.

Create a `WebSocketCall` with a `Request` and an `OkHttpClient` instance.
```java
WebSocketCall call = WebSocketCall.create(client, request);
```

A `WebSocketListener` will notify of the initial connection, server-sent messages, and any failures
on the connection.

Start the web socket by calling `enqueue` on `WebSocketCall` with the `WebSocketListener`.
```java
call.enqueue(new WebSocketListener() {
  // ...
});
```

*Note: This module's API should be considered experimental and may be subject to breaking changes
in future releases.*
