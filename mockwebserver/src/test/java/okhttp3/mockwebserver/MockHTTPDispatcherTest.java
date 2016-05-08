/*
 * Copyright (C) 2016 Google Inc.
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

package okhttp3.mockwebserver;

import okio.Buffer;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.ArrayList;

import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

public class MockHTTPDispatcherTest {

    @Test public void shouldMockARequest() throws Exception {
        MockHTTPDispatcher dispatcher = new MockHTTPDispatcher();
        MockRequest request = getMockRequest();

        Buffer bodyBuffer = new Buffer().writeString("{someObject: value}", Charset.defaultCharset());
        RecordedRequest recordedRequest = new RecordedRequest("POST /url ", null, new ArrayList<Integer>(), 100, bodyBuffer, 1, new Socket());

        dispatcher.mock(request);

        MockResponse response = dispatcher.dispatch(recordedRequest);
        assertThat(getResponseBody(response), is("{status:success}"));
    }

    @Test public void shouldReturnActualServedRequests() throws Exception {
        MockHTTPDispatcher dispatcher = new MockHTTPDispatcher();
        MockRequest request = getMockRequest();

        Buffer bodyBuffer = new Buffer().writeString("{someObject: value}", Charset.defaultCharset());
        RecordedRequest recordedRequest = new RecordedRequest("POST /url ", null, new ArrayList<Integer>(), 100, bodyBuffer, 1, new Socket());

        dispatcher.mock(request);

        dispatcher.dispatch(recordedRequest);

        assertThat(dispatcher.actualRequestsServedFor(request).size(), is(1));
        assertThat(dispatcher.actualRequestsServedFor(request).get(0), is(recordedRequest));
    }

    @Test public void shouldTellNumberOfTimesRequestExecuted() throws Exception {
        MockHTTPDispatcher dispatcher = new MockHTTPDispatcher();
        MockRequest request = getMockRequest();

        Buffer bodyBuffer = new Buffer().writeString("{someObject: value}", Charset.defaultCharset());
        RecordedRequest recordedRequest = new RecordedRequest("POST /url ", null, new ArrayList<Integer>(), 100, bodyBuffer, 1, new Socket());

        dispatcher.mock(request);

        dispatcher.dispatch(recordedRequest);
        dispatcher.dispatch(recordedRequest);

        assertTrue(dispatcher.isRequestServedForTimes(request, 2));
    }

    @Test public void shouldInterruptWaitForOnDemandRespond() throws Exception {
        MockHTTPDispatcher dispatcher = new MockHTTPDispatcher();
        MockRequest request = getMockRequest();

        Buffer bodyBuffer = new Buffer().writeString("{someObject: value}", Charset.defaultCharset());
        RecordedRequest recordedRequest = new RecordedRequest("POST /url ", null, new ArrayList<Integer>(), 100, bodyBuffer, 1, new Socket());

        dispatcher.mock(request);
        request.respondOnDemand();
        dispatcher.interrupt();

        MockResponse response = dispatcher.dispatch(recordedRequest);
        assertThat(getResponseBody(response), is("{status:success}"));
    }

    public static String getResponseBody(MockResponse response) {
        Buffer responseBuffer = response.getBody();
        String res = null;
        if (responseBuffer != null) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try {
                responseBuffer.copyTo(out);
                res = out.toString();
            } catch (IOException ignored) {

            } finally {
                try {
                    out.close();
                } catch (IOException ignored) {

                }
            }
        }
        return res;
    }

    private MockRequest getMockRequest() {
        return new MockRequestBuilder()
                .withHttpMethod("POST")
                .withPath("/url")
                .withRequestBody("{someObject: value}")
                .withResponse("{status:success}").build();
    }
}