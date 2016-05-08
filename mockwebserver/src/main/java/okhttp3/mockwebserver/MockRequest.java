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

    public void respondOnDemand() {
        this.shouldRespond = false;
    }

    public void respondNow() {
        this.shouldRespond = true;
    }

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
