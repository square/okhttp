package okhttp3;

import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLSocketFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

public class Jdk17Test {
    @Test
    public void testDeprecatedSslSocketFactory() {
        // https://github.com/square/okhttp/issues/6694

        SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();

        try {
            new OkHttpClient.Builder().sslSocketFactory(factory).build();
            fail();
        } catch (UnsupportedOperationException uoe) {
            // expected
            assertEquals("clientBuilder.sslSocketFactory(SSLSocketFactory) not supported on " +
                    "JDK 8 (>= 252) or JDK 9+",
                    uoe.getMessage());
        }
    }
}
