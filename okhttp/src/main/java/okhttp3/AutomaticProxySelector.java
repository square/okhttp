package okhttp3;

import sun.net.spi.DefaultProxySelector;

import java.io.IOException;
import java.net.Proxy;
import java.net.SocketAddress;
import java.net.URI;
import java.util.Collections;
import java.util.List;

public class AutomaticProxySelector extends DefaultProxySelector {

    public AutomaticProxySelector(List<String> noProxyForHosts, List<Proxy> proxies) {
        this.noProxyForHosts = noProxyForHosts;
        this.proxies = proxies;
    }

    private List<String> noProxyForHosts;
    private List<Proxy> proxies;

    @Override
    public List<Proxy> select(URI uri) {
        if (hostsContainHost(uri.getHost())) {
            return Collections.singletonList(Proxy.NO_PROXY);
        }

        return proxies;
    }

    @Override
    public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
        getDefault().connectFailed(uri, sa, ioe);
    }

    private boolean hostsContainHost(String host) {
        if (noProxyForHosts != null
                && !noProxyForHosts.isEmpty()) {
            for (String url : noProxyForHosts) {
                if (url.equals(host)) {
                    return true;
                }
            }
        }
        return false;
    }
}
