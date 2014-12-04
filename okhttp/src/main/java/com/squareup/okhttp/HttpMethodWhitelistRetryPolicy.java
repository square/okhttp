package com.squareup.okhttp;

import java.util.HashSet;
import java.util.Set;

/**
 * Allow request retry if request http method is on the whitelist.
 */
public final class HttpMethodWhitelistRetryPolicy implements RetryPolicy {

  private final Set<String> methods;

  private HttpMethodWhitelistRetryPolicy(Set<String> methods) {
    this.methods = methods;
  }

  /**
   * Get a retry policy that only allows retries for requests with the specified HTTP methods.
   *
   * @param methods HTTP methods to allow retries for
   * @return a retry policy
   */
  public static RetryPolicy forMethods(Set<String> methods) {
    return new HttpMethodWhitelistRetryPolicy(methods);
  }

  /**
   * @param methods methods
   * @return retry policy
   * @see com.squareup.okhttp.HttpMethodWhitelistRetryPolicy#forMethods(java.util.Set)
   */
  public static RetryPolicy forMethods(String... methods) {
    HashSet<String> set = new HashSet<>();
    for (String method : methods) {
      set.add(method);
    }

    return forMethods(set);
  }

  /**
   * @return a RetryPolicy that only allows retries for idempotent method types.
   * @see <a href="http://tools.ietf.org/html/rfc7231#section-4.2">RFC 7231 Section 4.2</a>
   */
  public static RetryPolicy forIdempotentOnly() {
    return HttpMethodWhitelistRetryPolicy
        .forMethods("OPTIONS", "GET", "HEAD", "TRACE", "PUT", "DELETE");
  }

  @Override
  public boolean allowRetry(Request request) {
    // RFC 7231 section 4: methods are case sensitive.
    return methods.contains(request.method());
  }
}
