package okhttp3;

import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;

public class ProtocolTest {
    @Test
    public void testGetKnown() throws IOException {
        assertEquals(Protocol.HTTP_1_0, Protocol.get("http/1.0"));
        assertEquals(Protocol.HTTP_1_1, Protocol.get("http/1.1"));
        assertEquals(Protocol.SPDY_3, Protocol.get("spdy/3.1"));
        assertEquals(Protocol.HTTP_2, Protocol.get("h2"));
        assertEquals(Protocol.H2C, Protocol.get("h2c"));
        assertEquals(Protocol.QUIC, Protocol.get("quic"));
    }

    @Test(expected = IOException.class)
    public void testGetUnknown() throws IOException {
        Protocol.get("tcp");
    }

    @Test
    public void testToString() throws IOException {
        assertEquals("http/1.0", Protocol.HTTP_1_0.toString());
        assertEquals("http/1.1", Protocol.HTTP_1_1.toString());
        assertEquals("spdy/3.1", Protocol.SPDY_3.toString());
        assertEquals("h2", Protocol.HTTP_2.toString());
        assertEquals("h2c", Protocol.H2C.toString());
        assertEquals("quic", Protocol.QUIC.toString());
    }
}
