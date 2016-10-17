package okhttp3.body;

import okio.BufferedSource;

public interface Readable {
  BufferedSource source();
}
