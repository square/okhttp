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
