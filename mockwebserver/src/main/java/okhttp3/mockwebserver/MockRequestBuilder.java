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

import okhttp3.MediaType;

import java.io.InputStream;

public class MockRequestBuilder {

    private final MockRequest mockRequest;

    public MockRequestBuilder() {
        mockRequest = new MockRequest();
    }

    public MockRequestBuilder withResponseContentType(MediaType responseContentType) {
        mockRequest.setResponseContentType(responseContentType);
        return this;
    }

    public MockRequestBuilder withPath(String path) {
        mockRequest.setPath(path);
        return this;
    }

    public MockRequestBuilder withHttpMethod(String httpMethod) {
        mockRequest.setHttpMethod(httpMethod);
        return this;
    }

    public MockRequestBuilder withRequestBody(String requestBody) {
        mockRequest.setRequestBody(requestBody);
        return this;
    }

    public MockRequestBuilder withResponse(String response) {
        mockRequest.setResponse(response);
        return this;
    }

    public MockRequestBuilder withResponseStream(InputStream responseStream) {
        mockRequest.setResponseStream(responseStream);
        return this;
    }

    public MockRequestBuilder respondOnDemand() {
        mockRequest.respondOnDemand();
        return this;
    }

    public MockRequest build() {
        return mockRequest;
    }
}
