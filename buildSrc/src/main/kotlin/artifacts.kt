object Projects {
  /** Returns the artifact ID for the project, or null if it is not published. */
  @JvmStatic
  fun publishedArtifactId(projectName: String): String? {
    return when (projectName) {
      "okhttp-logging-interceptor" -> "logging-interceptor"
      "mockwebserver" -> "mockwebserver3"
      "mockwebserver-junit4" -> "mockwebserver3-junit4"
      "mockwebserver-junit5" -> "mockwebserver3-junit5"
      "mockwebserver-deprecated" -> "mockwebserver"
      in listOf(
        "okcurl",
        "okhttp",
        "okhttp-bom",
        "okhttp-brotli",
        "okhttp-dnsoverhttps",
        "okhttp-sse",
        "okhttp-tls",
        "okhttp-urlconnection"
      ) -> projectName
      else -> null
    }
  }
}
