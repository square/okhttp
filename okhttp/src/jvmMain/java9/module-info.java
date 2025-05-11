module okhttp3 {
  requires transitive kotlin.stdlib;
  requires transitive okio;
  requires java.logging;
  exports okhttp3;
  exports okhttp3.internal to okhttp3.logging, okhttp3.sse, okhttp3.java.net.cookiejar, okhttp3.dnsoverhttps, mockwebserver3, okhttp3.mockwebserver, okhttp3.coroutines, okhttp3.tls;
  exports okhttp3.internal.platform to okhttp3.logging, okhttp3.java.net.cookiejar, okhttp3.dnsoverhttps, mockwebserver3, okhttp3.mockwebserver, okhttp3.tls;
  exports okhttp3.internal.http to okhttp3.logging, okhttp3.brotli, mockwebserver3;
  exports okhttp3.internal.http2 to mockwebserver3, okhttp3.mockwebserver;
  exports okhttp3.internal.concurrent to mockwebserver3, okhttp3.mockwebserver;
  exports okhttp3.internal.ws to mockwebserver3, okhttp3.mockwebserver;
  exports okhttp3.internal.publicsuffix to okhttp3.dnsoverhttps;
}
