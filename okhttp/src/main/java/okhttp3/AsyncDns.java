package okhttp3;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import javax.annotation.Nullable;

final class AsyncDns implements Dns {

  private final ExecutorService executor;

  AsyncDns(Builder builder) {
    this.executor = builder.executor;
  }

  public CompletableFuture<List<InetAddress>> lookupAsync(String hostName) {
    return CompletableFuture.supplyAsync(() -> {
      try {
        return Dns.SYSTEM.lookup(hostName);
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

    public AsyncDns build() {
      return new AsyncDns(this);
    }

    public Builder executor(ExecutorService executor) {
      this.executor = executor;
      return this;
    }
  }

}
