package com.squareup.okhttp;

/**
 * Retry policy that denies all retry attempts.
 */
public final class AllowNoneRetryPolicy implements RetryPolicy {
  @Override
  public boolean allowRetry(Request request) {
    return false;
  }
}
