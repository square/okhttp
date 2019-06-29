package okhttp3;

import okhttp3.internal.http.HttpHeaders;
import okhttp3.internal.http.HttpMethod;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@SuppressWarnings("ALL") public class PublicInternalApiTest {
  @Test public void permitsRequestBody() {
    assertTrue(HttpMethod.permitsRequestBody("POST"));
    assertFalse(HttpMethod.permitsRequestBody("GET"));
  }

  @Test public void requiresRequestBody() {
    assertTrue(HttpMethod.requiresRequestBody("PUT"));
    assertFalse(HttpMethod.requiresRequestBody("POST"));
  }

  @Test public void hasBody() {
    Response response = new Response.Builder().build();
    assertTrue(HttpHeaders.hasBody(response));
  }
}