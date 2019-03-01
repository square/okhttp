package okhttp3;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import javax.annotation.Nullable;

public final class AsyncDns implements Dns {

  private final ExecutorService executor;
  private final Dns dns;

  AsyncDns(Builder builder) {
    this.executor = builder.executor;
    this.dns = builder.dns;
  }

  public CompletableFuture<List<InetAddress>> lookupAsync(String hostName) {
    return CompletableFuture.supplyAsync(() -> {
      try {
        return dns.lookup(hostName);
      } catch (UnknownHostException e) {
        throw new CompletionException(e);
      }
    }, executor);
  }

  public @Override List<InetAddress> lookup(String hostname) throws UnknownHostException {
    CompletableFuture<List<InetAddress>> future = lookupAsync(hostname);
    try {
      return future.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Interrupted waiting for DNS resolution");
    } catch (ExecutionException e) {
      if (e.getCause() instanceof UnknownHostException)
        throw (UnknownHostException) e.getCause();

      throw new RuntimeException(e);
    }
  }

  public static final class Builder {
    @Nullable ExecutorService executor = null;
    Dns dns = Dns.SYSTEM;

    public AsyncDns build() {
      return new AsyncDns(this);
    }

    public Builder executor(ExecutorService executor) {
      this.executor = executor;
      return this;
    }

    public Builder dns(Dns dns) {
      this.dns = dns;
      return this;
    }
  }

}
