package com.squareup.okhttp.mockwebserver.exchanges;

import com.squareup.okhttp.mockwebserver.MockResponse;
import com.squareup.okhttp.mockwebserver.RecordedRequest;

/**
 * A helper class to allow responses to be assigned to requests fluently.
 */
public class MatchingRecordedRequest {

  private final ExpectedExchangesRule rule;

  private Predicate<RecordedRequest> matcher;

  MatchingRecordedRequest(final ExpectedExchangesRule rule,
                          final Predicate<RecordedRequest> requestMatcher) {
    this.rule = rule;
    this.matcher = requestMatcher;
  }

  /**
   * Add another feature that the request should match to return the given response.
   * @param matcher Matcher that the request must match.
   * @return <code>this</code>.
   */
  public MatchingRecordedRequest with(final Predicate<RecordedRequest> matcher) {
    this.matcher = new AndPredicate(this.matcher, matcher);
    return this;
  }

  /**
   * When a request matches the {@linkplain #with(Predicate) configured features}, return the
   * supplied response.
   * @param response Supplier of the response that should be returned when a request matches.
   */
  public void willReturn(final Supplier<MockResponse> response) {
    rule.stub(matcher, response);
  }

  /**
   * When a request matches the {@linkplain #with(Predicate) configured features}, return the
   * given response.
   * @param response Response that should be returned when a request matches.
   */
  public void willReturn(final MockResponse response) {
    willReturn(new MockResponseSupplier(response));
  }

  private static class AndPredicate implements Predicate<RecordedRequest> {

    private final Predicate<RecordedRequest> first;
    private final Predicate<RecordedRequest> second;

    private AndPredicate(final Predicate<RecordedRequest> first,
                         final Predicate<RecordedRequest> second) {
      this.first = first;
      this.second = second;
    }

    @Override
    public boolean test(final RecordedRequest recordedRequest) {
      return first.test(recordedRequest) && second.test(recordedRequest);
    }
  }

  private static class MockResponseSupplier implements Supplier<MockResponse> {

    private final MockResponse response;

    private MockResponseSupplier(final MockResponse response) {
      this.response = response;
    }

    @Override
    public MockResponse get() {
      return response;
    }
  }
}
