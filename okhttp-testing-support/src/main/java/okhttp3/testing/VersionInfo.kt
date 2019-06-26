package okhttp3.testing

object VersionInfo {
  val majorVersion: Int by lazy {
    when (val jvmSpecVersion = getJvmSpecVersion()) {
      "1.8" -> 8
      else -> jvmSpecVersion.toInt()
    }
  }

  fun getJvmSpecVersion(): String {
    return System.getProperty("java.specification.version", "unknown")
  }
}