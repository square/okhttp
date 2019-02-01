/*
 * Copyright (C) 2019 Square, Inc.
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
package okhttp3.internal.duplex;

import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import okio.Sink;

/**
 * A request body that is special in how it is <strong>transmitted</strong> on the network and in
 * the <strong>API contract</strong> between OkHttp and the application.
 *
 * <h3>Duplex Transmission</h3>
 *
 * <p>With regular HTTP calls the request always completes sending before the response may begin
 * receiving. With duplex the request and response may be interleaved! That is, request body bytes
 * may be sent after response headers or body bytes have been received.
 *
 * <p>Though any call may be initiated as a duplex call, only web servers that are specially
 * designed for this nonstandard interaction will use it. As of 2019-01, the only widely-used
 * implementation of this pattern is gRPC.
 *
 * <p>Because the encoding of interleaved data is not well-defined for HTTP/1, duplex request bodies
 * may only be used with HTTP/2. Calls to HTTP/1 servers will fail before the HTTP request is
 * transmitted.
 *
 * <p>Duplex APIs</p>
 *
 * <p>With regular request bodies it is not legal to write bytes to the sink passed to {@link
 * RequestBody#writeTo} after that method returns. For duplex sinks that condition is lifted. Such
 * writes occur on an application-provided thread and may occur concurrently with reads of the
 * {@link ResponseBody}.
 *
 * <p>Signal the end of a duplex request body by calling {@link Sink#close()}.
 */
public interface DuplexRequestBody {
  // TODO(jwilson): replace this internal marker interface with a public isDuplex() method?
}
