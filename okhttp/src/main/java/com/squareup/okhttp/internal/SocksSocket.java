
package com.squareup.okhttp.internal;

import com.squareup.okhttp.Dns;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.SocketAddress;

/**
 * An implementation of a minimal SOCKS5 client based on com/squareup/okhttp/SocksProxy.java and
 * <a href="http://tools.ietf.org/html/rfc1928">RFC1928</a>. It wraps around a socket and
 * establishes a connection through the proxy at connect time.
 */

public class SocksSocket extends Socket {
    Proxy proxy = null;

    public SocksSocket(Proxy proxy) {
        super();
        this.proxy = proxy;
    }

    @Override
    public void connect(SocketAddress endpoint, int timeout) throws IOException {
        if (proxy == null) {
            super.connect(endpoint, timeout);
        } else if (proxy.type() == Proxy.Type.SOCKS) {
            InetSocketAddress proxyaddr = ((InetSocketAddress) proxy.address());
            InetSocketAddress proxyaddrResolved = new InetSocketAddress(
                    Dns.SYSTEM.lookup(proxyaddr.getHostString()).get(0), proxyaddr.getPort());

            super.connect(proxyaddrResolved, timeout);
            InetSocketAddress a = (InetSocketAddress) endpoint;
            SocksProtocol.establish(this, a);
        }
    }
}
