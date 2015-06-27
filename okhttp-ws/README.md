OkHttp Web Sockets
==================

RFC6455-compliant web socket implementation.

Create a `WebSocketCall` with a `Request` and an `OkHttpClient` instance.
```java
WebSocketCall call = WebSocketCall.create(client, request);
```

A `WebSocketCallback` will notify of the initial connection success or failure.

Start the web socket by calling `enqueue` on `WebSocketCall` with the `WebSocketListener`.
```java
call.enqueue(new WebSocketCallback() {
  // ...
});
```

If the connection was successful, call `WebSocket.start()` with a `WebSocket.Listener` for
notification of peer messages.

*Note: This module's API should be considered experimental and may be subject to breaking changes
in future releases.*
