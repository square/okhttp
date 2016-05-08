package okhttp3.mockwebserver;

import okio.Buffer;
import org.junit.Test;

import java.net.Socket;
import java.nio.charset.Charset;
import java.util.ArrayList;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;


public class MockRequestTest {

    @Test public void shouldMatchTheRecordedRequest() throws Exception {
        MockRequest request = new MockRequestBuilder().withHttpMethod("POST").withPath("/url").withRequestBody("{someObject: value}").build();
        Buffer bodyBuffer = new Buffer().writeString("{someObject: value}", Charset.defaultCharset());
        RecordedRequest recordedRequest = new RecordedRequest("POST /url ", null, new ArrayList<Integer>(), 100, bodyBuffer, 1, new Socket());
        assertTrue(request.matches(recordedRequest));
    }

    @Test public void shouldNotMatchTheRecordedRequestWhenMethodDoesNotMatch() throws Exception {
        MockRequest request = new MockRequestBuilder().withHttpMethod("POST").withPath("/url").withRequestBody("{someObject: value}").build();
        Buffer bodyBuffer = new Buffer().writeString("{someObject: value}", Charset.defaultCharset());
        RecordedRequest recordedRequest = new RecordedRequest("GET /url ", null, new ArrayList<Integer>(), 100, bodyBuffer, 1, new Socket());
        assertFalse(request.matches(recordedRequest));
    }

    @Test public void shouldNotMatchTheRecordedRequestWhenUrlDoesNotMatch() throws Exception {
        MockRequest request = new MockRequestBuilder().withHttpMethod("POST").withPath("/url").withRequestBody("{someObject: value}").build();
        Buffer bodyBuffer = new Buffer().writeString("{someObject: value}", Charset.defaultCharset());
        RecordedRequest recordedRequest = new RecordedRequest("POST /different_url ", null, new ArrayList<Integer>(), 100, bodyBuffer, 1, new Socket());
        assertFalse(request.matches(recordedRequest));
    }

    @Test public void shouldNotMatchTheRecordedRequestWhenRequestBodyDoesNotMatch() throws Exception {
        MockRequest request = new MockRequestBuilder().withHttpMethod("POST").withPath("/url").withRequestBody("{someObject: value}").build();
        Buffer bodyBuffer = new Buffer().writeString("{someOtherObject: value}", Charset.defaultCharset());
        RecordedRequest recordedRequest = new RecordedRequest("POST /url ", null, new ArrayList<Integer>(), 100, bodyBuffer, 1, new Socket());
        assertFalse(request.matches(recordedRequest));
    }
}