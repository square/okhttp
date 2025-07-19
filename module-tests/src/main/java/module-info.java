@SuppressWarnings("module")
module okhttp3.modules {
  requires okhttp3;
  requires okhttp3.logging;
  requires jdk.crypto.ec;
  exports okhttp3.modules;
}
