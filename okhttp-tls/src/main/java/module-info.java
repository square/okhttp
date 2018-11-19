module tls {
    requires testing;
    requires okhttp;
    requires org.bouncycastle.provider;
    exports tls;
    exports tls.internal;
}