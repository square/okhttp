@SuppressWarnings("module")
module okhttp3.modules.test {
  requires okhttp3;
  requires okhttp3.logging;
  requires mockwebserver3;
  requires mockwebserver3.junit5;
  requires jdk.crypto.ec;
  requires org.junit.jupiter.api;
  requires okhttp3.modules;
  opens okhttp3.modules.test to org.junit.platform.commons;
}
