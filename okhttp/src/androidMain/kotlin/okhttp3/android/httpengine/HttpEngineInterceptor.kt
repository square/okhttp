/*
 * Copyright 2022 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package okhttp3.android.httpengine;

import android.util.Log;
import com.google.net.cronet.okhttptransport.RequestResponseConverter.CronetRequestAndOkHttpResponse;
import okhttp3.*;
import android.net.http.HttpEngine;
import android.net.http.UrlRequest;

import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * An OkHttp interceptor that redirects HTTP traffic to use Cronet instead of using the OkHttp
 * network stack.
 *
 * <p>The interceptor should be used as the last application interceptor to ensure that all other
 * interceptors are visited before sending the request on wire and after a response is returned.
 *
 * <p>The interceptor is a plug-and-play replacement for the OkHttp stack for the most part,
 * however, there are some caveats to keep in mind:
 *
 * <ol>
 *   <li>The entirety of OkHttp core is bypassed. This includes caching configuration and network
 *       interceptors.
 *   <li>Some response fields are not being populated due to mismatches between Cronet's and
 *       OkHttp's architecture. TODO(danstahr): add a concrete list).
 * </ol>
 */
public final class CronetInterceptor implements Interceptor, AutoCloseable {
  private static final String TAG = "CronetInterceptor";

  private static final int CANCELLATION_CHECK_INTERVAL_MILLIS = 500;

  private final RequestResponseConverter converter;
  private final Map<Call, UrlRequest> activeCalls = new ConcurrentHashMap<>();
  private final ScheduledExecutorService scheduledExecutor = new ScheduledThreadPoolExecutor(1);

  private CronetInterceptor(RequestResponseConverter converter) {
    this.converter = checkNotNull(converter);

    // TODO(danstahr): There's no other way to know if the call is canceled but polling
    //  (https://github.com/square/okhttp/issues/7164).
    ScheduledFuture<?> unusedFuture =
        scheduledExecutor.scheduleAtFixedRate(
            () -> {
              Iterator<Entry<Call, UrlRequest>> activeCallsIterator =
                  activeCalls.entrySet().iterator();

              while (activeCallsIterator.hasNext()) {
                try {
                  Entry<Call, UrlRequest> activeCall = activeCallsIterator.next();
                  if (activeCall.getKey().isCanceled()) {
                    activeCallsIterator.remove();
                    activeCall.getValue().cancel();
                  }
                } catch (RuntimeException e) {
                  Log.w(TAG, "Unable to propagate cancellation status", e);
                }
              }
            },
            CANCELLATION_CHECK_INTERVAL_MILLIS,
            CANCELLATION_CHECK_INTERVAL_MILLIS,
            MILLISECONDS);
  }

  @Override
  public Response intercept(Chain chain) throws IOException {
    if (chain.call().isCanceled()) {
      throw new IOException("Canceled");
    }

    Request request = chain.request();

    CronetRequestAndOkHttpResponse requestAndOkHttpResponse =
        converter.convert(request, chain.readTimeoutMillis(), chain.writeTimeoutMillis());

    activeCalls.put(chain.call(), requestAndOkHttpResponse.getRequest());

    try {
      requestAndOkHttpResponse.getRequest().start();
      return toInterceptorResponse(requestAndOkHttpResponse.getResponse(), chain.call());
    } catch (RuntimeException | IOException e) {
      // If the response is retrieved successfully the caller is responsible for closing
      // the response, which will remove it from the active calls map.
      activeCalls.remove(chain.call());
      throw e;
    }
  }

  /** Creates a {@link CronetInterceptor} builder. */
  public static Builder newBuilder(HttpEngine HttpEngine) {
    return new Builder(HttpEngine);
  }

  @Override
  public void close() {
    scheduledExecutor.shutdown();
  }

  /** A builder for {@link CronetInterceptor}. */
  public static final class Builder
      extends RequestResponseConverterBasedBuilder<Builder, CronetInterceptor> {

    Builder(HttpEngine HttpEngine) {
      super(HttpEngine, Builder.class);
    }

    /** Builds the interceptor. The same builder can be used to build multiple interceptors. */
    @Override
    public CronetInterceptor build(RequestResponseConverter converter) {
      return new CronetInterceptor(converter);
    }
  }

  private Response toInterceptorResponse(Response response, Call call) {
    checkNotNull(response.body());

    if (response.body() instanceof CronetInterceptorResponseBody) {
      return response;
    }

    return response
        .newBuilder()
        .body(new CronetInterceptorResponseBody(response.body(), call))
        .build();
  }

  private class CronetInterceptorResponseBody extends CronetTransportResponseBody {
    private final Call call;

    private CronetInterceptorResponseBody(ResponseBody delegate, Call call) {
      super(delegate);
      this.call = call;
    }

    @Override
    void customCloseHook() {
      activeCalls.remove(call);
    }
  }
}
