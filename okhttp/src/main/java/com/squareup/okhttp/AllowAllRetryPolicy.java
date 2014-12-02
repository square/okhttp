package com.squareup.okhttp;

/**
 * Retry policy that allows all retry attempts.
 */
public final class AllowAllRetryPolicy implements RetryPolicy {
  @Override
  public boolean allowRetry(Request request) {
    return true;
  }
}
