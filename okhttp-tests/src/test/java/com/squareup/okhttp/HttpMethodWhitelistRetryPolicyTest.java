package com.squareup.okhttp;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class HttpMethodWhitelistRetryPolicyTest {

  private RetryPolicy retryPolicy = HttpMethodWhitelistRetryPolicy.forIdempotentOnly();

  @Test
  public void allowsCorrectly() {
    Request request = new Request.Builder().url("foo").build();

    assertTrue(retryPolicy.allowRetry(request));
  }

  @Test
  public void deniesCorrectly() {
    Request request = new Request.Builder().url("foo").post(
        RequestBody.create(MediaType.parse("text/plain"), "foo")).build();

    assertFalse(retryPolicy.allowRetry(request));
  }
}
