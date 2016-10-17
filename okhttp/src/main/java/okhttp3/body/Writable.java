package okhttp3.body;

import okio.BufferedSink;

import java.io.IOException;

public interface Writable {
  void writeTo(BufferedSink sink) throws IOException;
}
