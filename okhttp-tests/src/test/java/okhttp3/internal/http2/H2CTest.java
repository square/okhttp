package okhttp3.internal.http2;

import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;

/**
 * @author jpitz
 */
public class H2CTest {
    @Test
    public void testProtocol() throws Exception {
        final Protocol maybeh2c = Protocol.get("h2c");
        assertEquals(Protocol.H2C, maybeh2c);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testOkHttpClientConstructionFallback() {
        //
        new OkHttpClient.Builder()
                .protocols(Arrays.asList(Protocol.H2C, Protocol.HTTP_1_1))
                .build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void testOkHttpClientConstructionDuplicates() {
        // Treating this use case as user error
        new OkHttpClient.Builder()
                .protocols(Arrays.asList(Protocol.H2C, Protocol.H2C))
                .build();
    }

    @Test
    public void testOkHttpClientConstructionSuccess() {
        final OkHttpClient okHttpClient = new OkHttpClient.Builder()
                .protocols(Arrays.asList(Protocol.H2C))
                .build();

        assertEquals(1, okHttpClient.protocols().size());
    }
}
