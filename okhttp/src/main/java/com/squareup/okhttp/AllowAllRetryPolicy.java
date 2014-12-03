package com.squareup.okhttp;

/**
 * Retry policy that allows all retry attempts.
 */
public enum AllowAllRetryPolicy implements RetryPolicy {

  INSTANCE;

  @Override
  public boolean allowRetry(Request request) {
    return true;
  }
}
