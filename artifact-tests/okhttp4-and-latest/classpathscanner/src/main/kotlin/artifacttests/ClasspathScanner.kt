package artifacttests

import java.io.File
import java.net.URI
import java.net.URL

/**
 * This extracts a list of directories and .jar files that make up the current class path. This code
 * is stolen from Okio's ResourceFileSystem.
 */
object ClasspathScanner {
  val roots: List<File> = run {
    val classloader = ClasspathScanner::class.java.classLoader
    classloader.getResources("").toList().mapNotNull { it.toFileRoot() } +
      classloader.getResources("META-INF/MANIFEST.MF").toList().mapNotNull { it.toJarRoot() }
  }

  private fun URL.toFileRoot(): File? {
    if (protocol != "file") return null // Ignore unexpected URLs.
    return File(toURI())
  }

  private fun URL.toJarRoot(): File? {
    val urlString = toString()
    if (!urlString.startsWith("jar:file:")) return null // Ignore unexpected URLs.

    // Given a URL like `jar:file:/tmp/foo.jar!/META-INF/MANIFEST.MF`, get the path to the archive
    // file, like `/tmp/foo.jar`.
    val suffixStart = urlString.lastIndexOf("!")
    if (suffixStart == -1) return null
    return File(URI.create(urlString.substring("jar:".length, suffixStart)))
  }
}
