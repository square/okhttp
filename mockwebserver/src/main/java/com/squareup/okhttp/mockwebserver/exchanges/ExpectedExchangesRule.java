package com.squareup.okhttp.mockwebserver.exchanges;

import com.squareup.okhttp.mockwebserver.MockResponse;
import com.squareup.okhttp.mockwebserver.RecordedRequest;
import com.squareup.okhttp.mockwebserver.rule.MockWebServerRule;
import org.junit.rules.ExternalResource;

/**
 * <p>
 *   A JUnit {@link org.junit.Rule &#64;Rule} that is responsible for maintaining the
 *   {@linkplain com.squareup.okhttp.mockwebserver.Dispatcher Dispatcher} which
 *   manages the {@linkplain MockResponse MockResponses} and which one to select for a request.
 * </p>
 * <p>
 *   Below is an example of using the {@linkplain MockWebServerRule} and
 *   {@linkplain ExpectedExchangesRule} rules together.
 * <pre>
 * public class ExampleTest {
 *
 *   &#64;ClassRule
 *   public static MockWebServerRule mockServer = new MockWebServerRule();
 *
 *   &#64;Rule
 *   public ExpectedExchangesRule expectedExchanges = new ExpectedExchangesRule(mockServer);
 *
 *   &#64;Test
 *   public void shouldTalkToDemoServer() {
 *   ...
 *   expectedExchanges.get("/demo/path")
 *     .willReturn(response().withStatus(200).withBody("Hello, World!"));
 *   ...
 *   }
 *
 * }
 * </pre>
 * </p>
 */
public class ExpectedExchangesRule extends ExternalResource {

  private final MockWebServerRule server;

  private ExchangeDispatcher dispatcher;

  /**
   * Construct a new {@linkplain ExpectedExchangesRule} using the server managed by the given
   * {@linkplain MockWebServerRule}.
   * @param server Rule that is managing the mock web server.
   */
  public ExpectedExchangesRule(final MockWebServerRule server) {
    this.server = server;
  }

  @Override
  protected void before() throws Throwable {
    dispatcher = new ExchangeDispatcher();
    server.get().setDispatcher(dispatcher);
  }

  /**
   * Begin matching a <code>GET</code> request for the given path.
   * @param path Path that the request should come on.
   * @return A {@linkplain MatchingRecordedRequest} to continue configuring the expected request
   * and dummy response.
   */
  public MatchingRecordedRequest get(final String path) {
    return new MatchingRecordedRequest(this, RequestMatchers.get(path));
  }

  /**
   * Begin matching a <code>POST</code> request for the given path.
   * @param path Path that the request should come on.
   * @return A {@linkplain MatchingRecordedRequest} to continue configuring the expected request
   * and dummy response.
   */
  public MatchingRecordedRequest post(final String path) {
      return new MatchingRecordedRequest(this, RequestMatchers.post(path));
  }

  /**
   * Begin matching a <code>PUT</code> request for the given path.
   * @param path Path that the request should come on.
   * @return A {@linkplain MatchingRecordedRequest} to continue configuring the expected request
   * and dummy response.
   */
  public MatchingRecordedRequest put(final String path) {
      return new MatchingRecordedRequest(this, RequestMatchers.put(path));
  }

  /**
   * Begin matching a <code>PATCH</code> request for the given path.
   * @param path Path that the request should come on.
   * @return A {@linkplain MatchingRecordedRequest} to continue configuring the expected request
   * and dummy response.
   */
  public MatchingRecordedRequest patch(final String path) {
      return new MatchingRecordedRequest(this, RequestMatchers.patch(path));
  }

  /**
   * Begin matching a <code>GET</code> request whose path matches the given
   * <code>pathMatcher</code>.
   * @param pathMatcher Matcher that the path that the request should come on must match.
   * @return A {@linkplain MatchingRecordedRequest} to continue configuring the expected request
   * and dummy response.
   */
  public MatchingRecordedRequest get(final Predicate<String> pathMatcher) {
    return new MatchingRecordedRequest(this, RequestMatchers.get(pathMatcher));
  }

  /**
   * Begin matching a <code>HEAD</code> request whose path matches the given
   * <code>pathMatcher</code>.
   * @param pathMatcher Matcher that the path that the request should come on must match.
   * @return A {@linkplain MatchingRecordedRequest} to continue configuring the expected request
   * and dummy response.
   */
  public MatchingRecordedRequest head(final Predicate<String> pathMatcher) {
    return new MatchingRecordedRequest(this, RequestMatchers.head(pathMatcher));
  }

  /**
   * Begin matching a <code>POST</code> request whose path matches the given
   * <code>pathMatcher</code>.
   * @param pathMatcher Matcher that the path that the request should come on must match.
   * @return A {@linkplain MatchingRecordedRequest} to continue configuring the expected request
   * and dummy response.
   */
  public MatchingRecordedRequest post(final Predicate<String> pathMatcher) {
    return new MatchingRecordedRequest(this, RequestMatchers.post(pathMatcher));
  }

  /**
   * Begin matching a <code>PUT</code> request whose path matches the given
   * <code>pathMatcher</code>.
   * @param pathMatcher Matcher that the path that the request should come on must match.
   * @return A {@linkplain MatchingRecordedRequest} to continue configuring the expected request
   * and dummy response.
   */
  public MatchingRecordedRequest put(final Predicate<String> pathMatcher) {
    return new MatchingRecordedRequest(this, RequestMatchers.put(pathMatcher));
  }

  /**
   * Begin matching a <code>DELETE</code> request whose path matches the given
   * <code>pathMatcher</code>.
   * @param pathMatcher Matcher that the path that the request should come on must match.
   * @return A {@linkplain MatchingRecordedRequest} to continue configuring the expected request
   * and dummy response.
   */
  public MatchingRecordedRequest delete(final Predicate<String> pathMatcher) {
    return new MatchingRecordedRequest(this, RequestMatchers.delete(pathMatcher));
  }

  /**
   * Begin matching a <code>TRACE</code> request whose path matches the given
   * <code>pathMatcher</code>.
   * @param pathMatcher Matcher that the path that the request should come on must match.
   * @return A {@linkplain MatchingRecordedRequest} to continue configuring the expected request
   * and dummy response.
   */
  public MatchingRecordedRequest trace(final Predicate<String> pathMatcher) {
    return new MatchingRecordedRequest(this, RequestMatchers.trace(pathMatcher));
  }

  /**
   * Begin matching a <code>OPTIONS</code> request whose path matches the given
   * <code>pathMatcher</code>.
   * @param pathMatcher Matcher that the path that the request should come on must match.
   * @return A {@linkplain MatchingRecordedRequest} to continue configuring the expected request
   * and dummy response.
   */
  public MatchingRecordedRequest options(final Predicate<String> pathMatcher) {
    return new MatchingRecordedRequest(this, RequestMatchers.options(pathMatcher));
  }

  /**
   * Begin matching a <code>CONNECT</code> request whose path matches the given
   * <code>pathMatcher</code>.
   * @param pathMatcher Matcher that the path that the request should come on must match.
   * @return A {@linkplain MatchingRecordedRequest} to continue configuring the expected request
   * and dummy response.
   */
  public MatchingRecordedRequest connect(final Predicate<String> pathMatcher) {
    return new MatchingRecordedRequest(this, RequestMatchers.connect(pathMatcher));
  }

  /**
   * Begin matching a <code>PATCH</code> request whose path matches the given
   * <code>pathMatcher</code>.
   * @param pathMatcher Matcher that the path that the request should come on must match.
   * @return A {@linkplain MatchingRecordedRequest} to continue configuring the expected request
   * and dummy response.
   */
  public MatchingRecordedRequest patch(final Predicate<String> pathMatcher) {
    return new MatchingRecordedRequest(this, RequestMatchers.patch(pathMatcher));
  }

  /**
   * Stub a request which matches the given <code>requestMatcher</code> and return the given
   * <code>response</code>.
   * @param requestMatcher Matcher that the request must match.
   * @param response Supplier of the response that should be returned when a request matches.
   */
  public void stub(final Predicate<RecordedRequest> requestMatcher,
                   final Supplier<MockResponse> response) {
    dispatcher.stub(requestMatcher, response);
  }

}
