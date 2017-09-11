package okhttp3;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.util.concurrent.TimeUnit;
import javax.net.SocketFactory;

public class XxX {
  public static void main(String[] args) throws IOException {
    SocketFactory socketFactory = new MyDelegatingSocketFactory();
    OkHttpClient.Builder builder = new OkHttpClient.Builder();
    builder.proxy(new Proxy(Proxy.Type.SOCKS, new InetSocketAddress("localhost", 2001)));
    builder.connectTimeout(10, TimeUnit.SECONDS);
    builder.socketFactory(socketFactory);
    OkHttpClient client = builder.build();

    Request req = new Request.Builder().url("https://google.com/robots.txt").build();
    try (Response response = client.newCall(req).execute()) {
      System.out.println(response.body.string().length());
    }
  }

  public static class MyDelegatingSocketFactory extends DelegatingSocketFactory {
    public MyDelegatingSocketFactory() {
      super(SocketFactory.getDefault());
    }

    @Override public Socket configureSocket(Socket socket) throws IOException {
      // set android
      //TrafficStats.tagSocket(socket);

      System.out.println("configured");

      return socket;
    }
  }
}
