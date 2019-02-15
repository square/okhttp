package okhttp3;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import javafx.util.Duration;
import org.junit.After;
import org.junit.Test;
import sun.net.util.IPAddressUtil;

import static java.util.Collections.singletonList;
import static org.junit.Assert.assertEquals;

public class AsyncDnsTest {

  private static final ExecutorService executor = Executors.newCachedThreadPool();

  @Test public void getOne() throws Exception {
    //MockDnsCache.when("a.bc", "1.2.3.4");
    MockDnsCache.when("a.bc", "1.2.3.4");

    final AsyncDns dns = new AsyncDns.Builder().executor(executor).build();
    final List<InetAddress> result = dns.lookupAsync("a.bc").get(5, TimeUnit.SECONDS);
    assertEquals(singletonList(address("1.2.3.4")), result);
  }

  @Test public void getMulti() throws Exception {
    //MockDnsCache.when("b.cd", "2.3.4.5", "3.4.5.6");
    MockDnsCache.when("b.cd", "2.3.4.5", "3.4.5.6");

    final AsyncDns dns = new AsyncDns.Builder().executor(executor).build();
    final List<InetAddress> result = dns.lookupAsync("b.cd").get(5, TimeUnit.SECONDS);
    result.sort(new InetAdressComparator());

    assertEquals(Arrays.asList(address("2.3.4.5"), address("3.4.5.6")), result);
  }
  
  @After public void clearDnsCache() {
    MockDnsCache.clear();
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
