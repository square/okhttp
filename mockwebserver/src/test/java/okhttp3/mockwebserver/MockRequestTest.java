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

import java.net.Socket;
import java.nio.charset.Charset;
import java.util.ArrayList;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;


public class MockRequestTest {

    @Test public void shouldMatchTheRecordedRequest() throws Exception {
        MockRequest request = new MockRequestBuilder().withHttpMethod("POST").withPath("/url").withRequestBody("{someObject: value}").build();
        Buffer bodyBuffer = new Buffer().writeString("{someObject: value}", Charset.defaultCharset());
        RecordedRequest recordedRequest = new RecordedRequest("POST /url ", null, new ArrayList<Integer>(), 100, bodyBuffer, 1, new Socket());
        assertTrue(request.matches(recordedRequest));
    }

    @Test public void shouldNotMatchTheRecordedRequestWhenMethodDoesNotMatch() throws Exception {
        MockRequest request = new MockRequestBuilder().withHttpMethod("POST").withPath("/url").withRequestBody("{someObject: value}").build();
        Buffer bodyBuffer = new Buffer().writeString("{someObject: value}", Charset.defaultCharset());
        RecordedRequest recordedRequest = new RecordedRequest("GET /url ", null, new ArrayList<Integer>(), 100, bodyBuffer, 1, new Socket());
        assertFalse(request.matches(recordedRequest));
    }

    @Test public void shouldNotMatchTheRecordedRequestWhenUrlDoesNotMatch() throws Exception {
        MockRequest request = new MockRequestBuilder().withHttpMethod("POST").withPath("/url").withRequestBody("{someObject: value}").build();
        Buffer bodyBuffer = new Buffer().writeString("{someObject: value}", Charset.defaultCharset());
        RecordedRequest recordedRequest = new RecordedRequest("POST /different_url ", null, new ArrayList<Integer>(), 100, bodyBuffer, 1, new Socket());
        assertFalse(request.matches(recordedRequest));
    }

    @Test public void shouldNotMatchTheRecordedRequestWhenRequestBodyDoesNotMatch() throws Exception {
        MockRequest request = new MockRequestBuilder().withHttpMethod("POST").withPath("/url").withRequestBody("{someObject: value}").build();
        Buffer bodyBuffer = new Buffer().writeString("{someOtherObject: value}", Charset.defaultCharset());
        RecordedRequest recordedRequest = new RecordedRequest("POST /url ", null, new ArrayList<Integer>(), 100, bodyBuffer, 1, new Socket());
        assertFalse(request.matches(recordedRequest));
    }
}