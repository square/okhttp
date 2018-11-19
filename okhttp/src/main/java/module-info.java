module okhttp {
    requires transitive okio;
    requires transitive java.logging;
    requires jsr305;
    requires conscrypt.openjdk.uber;
    requires animal.sniffer.annotations;
    requires android;
    exports okhttp3;
    opens okhttp3 to okhttp.urlconnection;
    exports okhttp3.internal;
    exports okhttp3.internal.cache;
    exports okhttp3.internal.http;
    exports okhttp3.internal.http2;
    exports okhttp3.internal.platform;
    exports okhttp3.internal.annotations;
    exports okhttp3.internal.publicsuffix;
    exports okhttp3.internal.io;
    exports okhttp3.internal.ws;
}