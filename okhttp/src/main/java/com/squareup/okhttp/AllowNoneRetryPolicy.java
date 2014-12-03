package com.squareup.okhttp;

/**
 * Retry policy that denies all retry attempts.
 */
public enum AllowNoneRetryPolicy implements RetryPolicy {

  INSTANCE;

  @Override
  public boolean allowRetry(Request request) {
    return false;
  }
}
