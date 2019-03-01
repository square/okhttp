package okhttp3.internal.connection;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import okhttp3.AsyncDns;
import okhttp3.Dns;
import org.junit.AfterClass;

public final class AsyncRouteSelectorTest extends RouteSelectorTest {

  private static final ExecutorService executor = Executors.newCachedThreadPool();

  private final AsyncDns asyncDns;

  public AsyncRouteSelectorTest() {
    super();
    asyncDns = new AsyncDns.Builder().executor(executor).dns(dns).build();
  }

  @AfterClass public static void shutdownExecutor() {
    executor.shutdownNow();
  }

  @Override protected Dns getTestDns() {
    return asyncDns;
  }

}
