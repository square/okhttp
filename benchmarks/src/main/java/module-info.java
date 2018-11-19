module benchmarks {
    requires okhttp;
    requires tls;
    requires httpcore;
    requires httpclient;
    requires netty.transport;
    requires netty.buffer;
    requires netty.codec.http;
    requires netty.handler;
    requires caliper;
    requires mockwebserver;
}