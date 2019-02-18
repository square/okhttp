package okhttp3;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Test;

import static java.util.Collections.singletonList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class AsyncDnsTest {

  private static final ExecutorService executor = Executors.newCachedThreadPool();

  @Test public void getOne() throws Exception {
    MockDnsCache.when("a.bc").thenReturn("1.2.3.4");

    final AsyncDns dns = new AsyncDns.Builder().executor(executor).build();
    final List<InetAddress> result = dns.lookupAsync("a.bc").get(10, TimeUnit.SECONDS);
    assertEquals(singletonList(address("1.2.3.4")), result);
  }

  @Test public void getMulti() throws Exception {
    MockDnsCache.when("b.cd").thenReturn("2.3.4.5", "3.4.5.6");

    final AsyncDns dns = new AsyncDns.Builder().executor(executor).build();
    final List<InetAddress> result = dns.lookupAsync("b.cd").get(10, TimeUnit.SECONDS);
    result.sort(new InetAdressComparator());

    assertEquals(Arrays.asList(address("2.3.4.5"), address("3.4.5.6")), result);
  }

  //@Test public void cancel() {
  //  MockDnsCache.when("cancel.do").thenAnswer(() -> {
  //    try {
  //      Thread.sleep(2000);
  //    } catch (InterruptedException e) {
  //      Thread.currentThread().interrupt();
  //    }
  //    return null;
  //  });
  //
  //  final AsyncDns dns = new AsyncDns.Builder().executor(executor).build();
  //  final CompletableFuture<List<InetAddress>> future = dns.lookupAsync("cancel.do");
  //  if (!future.isDone())
  //    future.cancel(true);
  //  else
  //    fail("Should not be done");
  //}

  @Test(expected = TimeoutException.class) public void timeout() throws Exception {
    MockDnsCache.when("timeout.do").thenAnswer(() -> {
      try {
        Thread.sleep(4000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      return null;
    });

    final AsyncDns dns = new AsyncDns.Builder().executor(executor).build();
    dns.lookupAsync("timeout.do").get(2, TimeUnit.SECONDS);
  }
  
  @After public void clearDnsCache() {
    MockDnsCache.clear();
  }

  @AfterClass public static void shutdownExecutor() {
    executor.shutdownNow();
  }

  private static InetAddress address(String host) {
    try {
      return InetAddress.getByName(host);
    } catch (UnknownHostException e) {
      // impossible for IP addresses
      throw new RuntimeException(e);
    }
  }

  private static final class InetAdressComparator implements Comparator<InetAddress> {
    @Override
    public int compare(InetAddress address1, InetAddress address2) {
      return address1.getHostName().compareTo(address2.getHostName());
    }
  }

}
