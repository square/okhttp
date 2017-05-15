package com.squareup.okhttp.mockwebserver.exchanges;

import com.squareup.okhttp.mockwebserver.Dispatcher;
import com.squareup.okhttp.mockwebserver.MockResponse;
import com.squareup.okhttp.mockwebserver.RecordedRequest;

import java.net.HttpURLConnection;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;
import java.util.logging.Logger;

class ExchangeDispatcher extends Dispatcher {

  private static final Logger LOG = Logger.getLogger(ExchangeDispatcher.class.getName());
  private static final MockResponse NOT_FOUND =
          new MockResponse().setResponseCode(HttpURLConnection.HTTP_NOT_FOUND);

  private final Queue<Exchange> exchanges = new ConcurrentLinkedQueue<>();

  void stub(Predicate<RecordedRequest> request, Supplier<MockResponse> response) {
    exchanges.add(new Exchange(request, response));
  }

  @Override
  public MockResponse dispatch(final RecordedRequest request) throws InterruptedException {
    final MockResponse response = getResponse(request);

    if (LOG.isLoggable(Level.FINE)) {
      LOG.log(Level.FINE, "served " + request + " with " + response);
    }

    return response;
  }

  private MockResponse getResponse(final RecordedRequest request) {
    for (final Exchange exchange : exchanges) {
      if (exchange.getRequest().test(request)) {
        return exchange.getResponse();
      }
    }

    return NOT_FOUND;
  }

  private static class Exchange {
    private final Predicate<RecordedRequest> request;
    private final Supplier<MockResponse> response;

    private Exchange(final Predicate<RecordedRequest> request,
                     final Supplier<MockResponse> response) {
      this.request = request;
      this.response = response;
    }

    private Predicate<RecordedRequest> getRequest() {
      return request;
    }

    private MockResponse getResponse() {
      return response.get();
    }
  }
}
