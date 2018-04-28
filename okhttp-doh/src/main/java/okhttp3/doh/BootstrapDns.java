package okhttp3.doh;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;
import okhttp3.Dns;

public class BootstrapDns implements Dns {
  private final String dnsHost;
  private final List<InetAddress> dnsServers;

  public BootstrapDns(String dnsHost, InetAddress... dnsServers) {
    this.dnsHost = dnsHost;
    this.dnsServers = Collections.unmodifiableList(Arrays.asList(dnsServers));
  }

  @Override public List<InetAddress> lookup(String hostname) throws UnknownHostException {
    if (hostname.equals(dnsHost)) {
      return dnsServers;
    }

    throw new UnknownHostException(hostname);
  }

  public @Nullable List<InetAddress> get(String hostname) {
    if (hostname.equals(dnsHost)) {
      return dnsServers;
    }

    return null;
  }
}
