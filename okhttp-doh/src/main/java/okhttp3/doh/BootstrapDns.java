package okhttp3.doh;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import okhttp3.Dns;

/**
 * Internal Bootstrap DNS implementation for handling initial connection to DNS over HTTPS server.
 *
 * Returns hardcoded results for the known host.
 */
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
}
