module okhttp3 {
  requires transitive kotlin.stdlib;
  requires transitive okio;
  exports okhttp3;
  opens okhttp3.internal;
  opens okhttp3.internal.platform;
  opens okhttp3.internal.http;
}
