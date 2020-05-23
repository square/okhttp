package okhttp3;

import okhttp3.mockwebserver.MockResponse;
import okio.Buffer;
import okio.BufferedSink;
import okio.Utf8;
import org.junit.AssumptionViolatedException;

import javax.annotation.Nullable;
import java.io.IOException;

import static okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_END;

public enum TransferKind {
    CHUNKED {
        @Override void setBody(MockResponse response, Buffer content, int chunkSize) {
            response.setChunkedBody(content, chunkSize);
        }

        @Override RequestBody newRequestBody(String body) {
            return new RequestBody() {
                @Override public long contentLength() {
                    return -1L;
                }

                @Override public @Nullable
                MediaType contentType() {
                    return null;
                }

                @Override public void writeTo(BufferedSink sink) throws IOException {
                    sink.writeUtf8(body);
                }
            };
        }
    },
    FIXED_LENGTH {
        @Override void setBody(MockResponse response, Buffer content, int chunkSize) {
            response.setBody(content);
        }

        @Override RequestBody newRequestBody(String body) {
            return new RequestBody() {
                @Override public long contentLength() {
                    return Utf8.size(body);
                }

                @Override public @Nullable MediaType contentType() {
                    return null;
                }

                @Override public void writeTo(BufferedSink sink) throws IOException {
                    sink.writeUtf8(body);
                }
            };
        }
    },
    END_OF_STREAM {
        @Override void setBody(MockResponse response, Buffer content, int chunkSize) {
            response.setBody(content);
            response.setSocketPolicy(DISCONNECT_AT_END);
            response.removeHeader("Content-Length");
        }

        @Override RequestBody newRequestBody(String body) {
            throw new AssumptionViolatedException("END_OF_STREAM not implemented for requests");
        }
    };

    abstract void setBody(MockResponse response, Buffer content, int chunkSize) throws IOException;

    abstract RequestBody newRequestBody(String body);

    void setBody(MockResponse response, String content, int chunkSize) throws IOException {
        setBody(response, new Buffer().writeUtf8(content), chunkSize);
    }
};
