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
package okhttp3.benchmarks;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
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
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLEngine;
import okhttp3.HttpUrl;
import okhttp3.internal.SslContextBuilder;
import okhttp3.internal.tls.SslClient;

/** Netty isn't an HTTP client, but it's almost one. */
class NettyHttpClient implements HttpClient {
  private static final boolean VERBOSE = false;

  // Guarded by this. Real apps need more capable connection management.
  private final Deque<HttpChannel> freeChannels = new ArrayDeque<>();
  private final Deque<HttpUrl> backlog = new ArrayDeque<>();

  private int totalChannels = 0;
  private int concurrencyLevel;
  private int targetBacklog;
  private Bootstrap bootstrap;

  @Override public void prepare(final Benchmark benchmark) {
    this.concurrencyLevel = benchmark.concurrencyLevel;
    this.targetBacklog = benchmark.targetBacklog;

    ChannelInitializer<SocketChannel> channelInitializer = new ChannelInitializer<SocketChannel>() {
      @Override public void initChannel(SocketChannel channel) throws Exception {
        ChannelPipeline pipeline = channel.pipeline();

        if (benchmark.tls) {
          SslClient sslClient = SslContextBuilder.localhost();
          SSLEngine engine = sslClient.sslContext.createSSLEngine();
          engine.setUseClientMode(true);
          pipeline.addLast("ssl", new SslHandler(engine));
        }

        pipeline.addLast("codec", new HttpClientCodec());
        pipeline.addLast("inflater", new HttpContentDecompressor());
        pipeline.addLast("handler", new HttpChannel(channel));
      }
    };

    bootstrap = new Bootstrap();
    bootstrap.group(new NioEventLoopGroup(concurrencyLevel))
        .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
        .channel(NioSocketChannel.class)
        .handler(channelInitializer);
  }

  @Override public void enqueue(HttpUrl url) throws Exception {
    HttpChannel httpChannel = null;
    synchronized (this) {
      if (!freeChannels.isEmpty()) {
        httpChannel = freeChannels.pop();
      } else if (totalChannels < concurrencyLevel) {
        totalChannels++; // Create a new channel. (outside of the synchronized block).
      } else {
        backlog.add(url); // Enqueue this for later, to be picked up when another request completes.
        return;
      }
    }
    if (httpChannel == null) {
      Channel channel = bootstrap.connect(url.host(), url.port())
          .sync().channel();
      httpChannel = (HttpChannel) channel.pipeline().last();
    }
    httpChannel.sendRequest(url);
  }

  @Override public synchronized boolean acceptingJobs() {
    return backlog.size() < targetBacklog || hasFreeChannels();
  }

  private boolean hasFreeChannels() {
    int activeChannels = totalChannels - freeChannels.size();
    return activeChannels < concurrencyLevel;
  }

  private void release(HttpChannel httpChannel) {
    HttpUrl url;
    synchronized (this) {
      url = backlog.pop();
      if (url == null) {
        // There were no URLs in the backlog. Pool this channel for later.
        freeChannels.push(httpChannel);
        return;
      }
    }

    // We removed a URL from the backlog. Schedule it right away.
    httpChannel.sendRequest(url);
  }

  class HttpChannel extends SimpleChannelInboundHandler<HttpObject> {
    private final SocketChannel channel;
    byte[] buffer = new byte[1024];
    int total;
    long start;

    public HttpChannel(SocketChannel channel) {
      this.channel = channel;
    }

    private void sendRequest(HttpUrl url) {
      start = System.nanoTime();
      total = 0;
      HttpRequest request = new DefaultFullHttpRequest(
          HttpVersion.HTTP_1_1, HttpMethod.GET, url.encodedPath());
      request.headers().set(HttpHeaders.Names.HOST, url.host());
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
