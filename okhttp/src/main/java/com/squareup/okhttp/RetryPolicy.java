package com.squareup.okhttp;

/**
 * Allows customizing when retries are allowed. You may wish to disable retries on requests for
 * certain URLs, or with certain HTTP methods.
 *
 * Implementations should be thread safe.
 *
 * @see com.squareup.okhttp.HttpMethodWhitelistRetryPolicy
 * @see com.squareup.okhttp.AllowAllRetryPolicy
 * @see com.squareup.okhttp.AllowNoneRetryPolicy
 */
public interface RetryPolicy {

  /**
   * @param request request that's about to be retried
   * @return true iff a retry is desired
   */
  boolean allowRetry(Request request);
}
