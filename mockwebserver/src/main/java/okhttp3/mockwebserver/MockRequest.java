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
import okio.Buffer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;


public class MockRequest {
    MediaType responseContentType;
    String path;
    String httpMethod;
    String response;
    String requestBody;
    InputStream responseStream;
    private boolean shouldRespond = true;

    public MockRequest(String path, String httpMethod, String response) {
        this.path = path;
        this.httpMethod = httpMethod;
        this.response = response;
        this.responseContentType = MediaType.parse("application/json");
    }

    public MockRequest(String path, String httpMethod, String requestBody, String response) {
        this.path = path;
        this.httpMethod = httpMethod;
        this.response = response;
        this.requestBody = requestBody;
        this.responseContentType = MediaType.parse("application/json");
    }

    public MockRequest(String path, String httpMethod, InputStream responseStream, MediaType responseContentType) {
        this.path = path;
        this.httpMethod = httpMethod;
        this.responseStream = responseStream;
        this.responseContentType = responseContentType;
    }

    MockRequest() {
        this.responseContentType = MediaType.parse("application/json");
    }

    public void setResponseContentType(MediaType responseContentType) {
        this.responseContentType = responseContentType;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public void setHttpMethod(String httpMethod) {
        this.httpMethod = httpMethod;
    }

    public void setResponse(String response) {
        this.response = response;
    }

    public void setResponseStream(InputStream responseStream) {
        this.responseStream = responseStream;
    }

    public void setRequestBody(String requestBody) {
        this.requestBody = requestBody;
    }

    /**
     * Allows to control the respond time of a request, Respond the response with {@link #respondNow()}
     */
    public void respondOnDemand() {
        this.shouldRespond = false;
    }

    /**
     * Tells dispatcher to dispatch the response immediately
     */
    public void respondNow() {
        this.shouldRespond = true;
    }

    /**
     * Check if a recorded request matchers this mock request
     */
    boolean matches(RecordedRequest r) {
        String actualRequestBody = getRequestBodyString(r);
        return r.getPath().equals(path)
                && r.getMethod().equalsIgnoreCase(httpMethod)
                && (requestBody==null || (actualRequestBody !=null && actualRequestBody.contains(requestBody)));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MockRequest that = (MockRequest) o;
        if (path != null ? !path.equals(that.path) : that.path != null) return false;
        if (httpMethod != null ? !httpMethod.equals(that.httpMethod) : that.httpMethod != null)
            return false;
        return !(requestBody != null ? !requestBody.equals(that.requestBody) : that.requestBody != null);
    }

    @Override
    public int hashCode() {
        int result = path != null ? path.hashCode() : 0;
        result = 31 * result + (httpMethod != null ? httpMethod.hashCode() : 0);
        result = 31 * result + (requestBody != null ? requestBody.hashCode() : 0);
        return result;
    }

    public boolean shouldRespond() {
        return shouldRespond;
    }

    public static String getRequestBodyString(RecordedRequest recordedRequest) {
        Buffer actualRequestBody = recordedRequest.getBody();
        String actualRequestBodyString = null;
        if(actualRequestBody!=null) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try {
                actualRequestBody.copyTo(out);
                actualRequestBodyString = out.toString();
            } catch (IOException ignored) {

            } finally {
                try {
                    out.close();
                } catch (IOException ignored) {

                }
            }
        }
        return actualRequestBodyString;
    }
}
