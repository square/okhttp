/*
 * Copyright (C) 2014 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.okhttp.benchmarks;

import com.squareup.okhttp.internal.SslContextBuilder;
import com.squareup.okhttp.internal.Util;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpContentDecompressor;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.ssl.SslHandler;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;

/** Netty isn't an HTTP client, but it's almost one. */
class NettyHttpClient implements HttpClient {
  private static final boolean VERBOSE = false;

  // Guarded by this. Real apps need more capable connection management.
  private final List<HttpChannel> freeChannels = new ArrayList<HttpChannel>();
  private int totalChannels = 0;

  private int concurrencyLevel;
  private Bootstrap bootstrap;

  @Override public void prepare(final Benchmark benchmark) {
    this.concurrencyLevel = benchmark.concurrencyLevel;

    ChannelInitializer<SocketChannel> channelInitializer = new ChannelInitializer<SocketChannel>() {
      @Override public void initChannel(SocketChannel channel) throws Exception {
        ChannelPipeline pipeline = channel.pipeline();

        if (benchmark.tls) {
          SSLContext sslContext = SslContextBuilder.localhost();
          SSLEngine engine = sslContext.createSSLEngine();
          engine.setUseClientMode(true);
          pipeline.addLast("ssl", new SslHandler(engine));
        }

        pipeline.addLast("codec", new HttpClientCodec());
        pipeline.addLast("inflater", new HttpContentDecompressor());
        pipeline.addLast("handler", new HttpChannel(channel));
      }
    };

    EventLoopGroup group = new NioEventLoopGroup();
    bootstrap = new Bootstrap();
    bootstrap.group(group)
        .channel(NioSocketChannel.class)
        .handler(channelInitializer);
  }

  @Override public void enqueue(URL url) throws Exception {
    acquireChannel(url).sendRequest(url);
  }

  @Override public synchronized boolean acceptingJobs() {
    int activeChannels = totalChannels - freeChannels.size();
    return activeChannels < concurrencyLevel;
  }

  private HttpChannel acquireChannel(URL url) throws InterruptedException {
    synchronized (this) {
      if (!freeChannels.isEmpty()) {
        return freeChannels.remove(freeChannels.size() - 1);
      } else {
        totalChannels++;
      }
    }

    Channel channel = bootstrap.connect(url.getHost(), Util.getEffectivePort(url)).sync().channel();
    return (HttpChannel) channel.pipeline().last();
  }

  private synchronized void release(HttpChannel httpChannel) {
    freeChannels.add(httpChannel);
  }

  class HttpChannel extends SimpleChannelInboundHandler<HttpObject> {
    private final SocketChannel channel;
    byte[] buffer = new byte[1024];
    int total;
    long start;

    public HttpChannel(SocketChannel channel) {
      this.channel = channel;
    }

    private void sendRequest(URL url) {
      start = System.nanoTime();
      total = 0;
      HttpRequest request = new DefaultFullHttpRequest(
          HttpVersion.HTTP_1_1, HttpMethod.GET, url.getPath());
      request.headers().set(HttpHeaders.Names.HOST, url.getHost());
      request.headers().set(HttpHeaders.Names.ACCEPT_ENCODING, HttpHeaders.Values.GZIP);
      channel.writeAndFlush(request);
    }

    @Override protected void channelRead0(
        ChannelHandlerContext context, HttpObject message) throws Exception {
      if (message instanceof HttpResponse) {
        receive((HttpResponse) message);
      }
      if (message instanceof HttpContent) {
        receive((HttpContent) message);
        if (message instanceof LastHttpContent) {
          release(this);
        }
      }
    }

    @Override public void channelInactive(ChannelHandlerContext ctx) throws Exception {
      super.channelInactive(ctx);
    }

    void receive(HttpResponse response) {
      // Don't do anything with headers.
    }

    void receive(HttpContent content) {
      // Consume the response body.
      ByteBuf byteBuf = content.content();
      for (int toRead; (toRead = byteBuf.readableBytes()) > 0; ) {
        byteBuf.readBytes(buffer, 0, Math.min(buffer.length, toRead));
        total += toRead;
      }

      if (VERBOSE && content instanceof LastHttpContent) {
        long finish = System.nanoTime();
        System.out.println(String.format("Transferred % 8d bytes in %4d ms",
            total, TimeUnit.NANOSECONDS.toMillis(finish - start)));
      }
    }

    @Override public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
      System.out.println("Failed: " + cause);
    }
  }
}
