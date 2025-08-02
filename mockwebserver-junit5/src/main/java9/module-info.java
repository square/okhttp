@SuppressWarnings("module")
module mockwebserver3.junit5 {
  requires okhttp3;
  opens mockwebserver3.junit5.internal;
}
