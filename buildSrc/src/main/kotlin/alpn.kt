// https://www.eclipse.org/jetty/documentation/current/alpn-chapter.html#alpn-versions
fun alpnBootVersionForPatchVersion(javaVersion: String, patchVersion: Int): String? {
  return when (patchVersion) {
    in 0..24 -> "8.1.0.v20141016"
    in 25..30 -> "8.1.2.v20141202"
    in 31..50 -> "8.1.3.v20150130"
    in 51..59 -> "8.1.4.v20150727"
    in 60..64 -> "8.1.5.v20150921"
    in 65..70 -> "8.1.6.v20151105"
    in 71..77 -> "8.1.7.v20160121"
    in 78..101 -> "8.1.8.v20160420"
    in 102..111 -> "8.1.9.v20160720"
    in 112..120 -> "8.1.10.v20161026"
    in 121..160 -> "8.1.11.v20170118"
    in 161..181 -> "8.1.12.v20180117"
    in 191..242 -> "8.1.13.v20181017"
    else -> null 
  }
}

/**
 * Returns the alpn-boot version specific to this OpenJDK 8 JVM, or null if this is not a Java 8 VM.
 * https://github.com/xjdr/xio/blob/master/alpn-boot.gradle
 */
fun alpnBootVersion(): String? {
  val version = System.getProperty("alpn.boot.version")

  if (version != null) {
    return version
  }

  val javaVersion = System.getProperty("java.version")
  val match = "1\\.8\\.0_(\\d+)(-.*)?".toRegex().find(javaVersion) ?: return null
  return alpnBootVersionForPatchVersion(javaVersion, match.groupValues.first().toInt())
}
