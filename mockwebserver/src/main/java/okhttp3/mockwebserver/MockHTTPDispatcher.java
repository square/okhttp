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

import okhttp3.mockwebserver.WaitForCondition.Condition;
import okio.Buffer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static okhttp3.mockwebserver.WaitForCondition.waitFor;

public class MockHTTPDispatcher extends Dispatcher {
    private static final int MAX_WAIT_FOR_RESPONDING = 10000;
    private Map<MockRequest, MockResponse> requestResponseMap;
    private Map<MockRequest, List<RecordedRequest>> servedRequests;
    private boolean interrupted = false;

    public MockHTTPDispatcher() {
        requestResponseMap = new HashMap<MockRequest, MockResponse>();
        servedRequests = new HashMap<MockRequest, List<RecordedRequest>>();
    }

    /**
     * Mocks a request.
     */
    public MockHTTPDispatcher mock(MockRequest mockRequest) throws IOException {
        MockResponse mockResponse = new MockResponse()
                .addHeader("Content-Type", mockRequest.responseContentType.toString());
        if (mockRequest.response != null)
            mockResponse.setBody(mockRequest.response);
        else if (mockRequest.responseStream != null)
            mockResponse.setBody(new Buffer().readFrom(mockRequest.responseStream));

        requestResponseMap.put(mockRequest, mockResponse);
        servedRequests.put(mockRequest, new ArrayList<RecordedRequest>());
        return this;
    }

    /**
     * Check if a request is served for x number of times.
     */
    public boolean isRequestServedForTimes(MockRequest mockRequest, Integer times) {
        synchronized (this) {
            List<RecordedRequest> recordedRequests = servedRequests.get(mockRequest);
            return recordedRequests != null && times.equals(recordedRequests.size());
        }
    }

    /**
     * Interrupt dispatcher to respond to request immediately.
     */
    public void interrupt() {
        interrupted = true;
    }

    /**
     * Returns the list of actual served requests for a mock request.
     */
    public List<RecordedRequest> actualRequestsServedFor(MockRequest mockRequest) {
        return servedRequests.get(mockRequest);
    }

    /**
     * Waits for shouldRespond flag to be true and dispatches a recorded request.
     * Default wait time is 10000 millis.
     * Returns 404 response when recorded request is not mocked.
     */
    @Override
    public MockResponse dispatch(RecordedRequest recordedRequest) {
        synchronized (this) {
            for (final MockRequest mockRequest : requestResponseMap.keySet()) {
                if (mockRequest.matches(recordedRequest)) {
                    waitFor(readyToRespond(mockRequest), MAX_WAIT_FOR_RESPONDING);
                    servedRequests.get(mockRequest).add(recordedRequest);
                    return requestResponseMap.get(mockRequest);
                }
            }
            return new MockResponse().setResponseCode(404);
        }
    }

    private Condition readyToRespond(final MockRequest mockRequest) {
        return new Condition() {
            @Override
            public boolean isSatisfied() {
                return mockRequest.shouldRespond() || interrupted;
            }
        };
    }
}
