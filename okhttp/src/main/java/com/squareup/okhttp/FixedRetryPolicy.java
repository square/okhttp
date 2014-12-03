package com.squareup.okhttp;

/**
 * A RetryPolicy that always returns the same thing.
 */
public final class FixedRetryPolicy implements RetryPolicy {
  private static final FixedRetryPolicy ALLOW_ALL = new FixedRetryPolicy(true);
  private static final FixedRetryPolicy ALLOW_NONE = new FixedRetryPolicy(false);

  private final boolean allow;

  private FixedRetryPolicy(boolean allow) {
    this.allow = allow;
  }

  /**
   * @return a RetryPolicy that allows all retriesd
   */
  public static RetryPolicy getAllowAllPolicy() {
    return ALLOW_ALL;
  }

  /**
   * @return a RetryPolicy that denies all retries
   */
  public static RetryPolicy getAllowNonePolicy() {
    return ALLOW_NONE;
  }

  @Override
  public boolean allowRetry(Request request) {
    return allow;
  }
}
