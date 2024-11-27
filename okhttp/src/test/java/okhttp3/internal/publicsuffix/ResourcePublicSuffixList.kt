package okhttp3.internal.publicsuffix

import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.internal.platform.Platform
import okio.ByteString
import okio.FileSystem
import okio.GzipSource
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer

internal class ResourcePublicSuffixList(
  val path: Path = PUBLIC_SUFFIX_RESOURCE,
  val fileSystem: FileSystem = FileSystem.RESOURCES,
) : PublicSuffixList {
  /** True after we've attempted to read the list for the first time. */
  private val listRead = AtomicBoolean(false)

  /** Used for concurrent threads reading the list for the first time. */
  private val readCompleteLatch = CountDownLatch(1)

  // The lists are held as a large array of UTF-8 bytes. This is to avoid allocating lots of strings
  // that will likely never be used. Each rule is separated by '\n'. Please see the
  // PublicSuffixListGenerator class for how these lists are generated.
  // Guarded by this.
  override lateinit var bytes: ByteString
  override lateinit var exceptionBytes: ByteString

  @Throws(IOException::class)
  private fun readTheList() {
    var publicSuffixListBytes: ByteString?
    var publicSuffixExceptionListBytes: ByteString?

    try {
      GzipSource(fileSystem.source(path)).buffer().use { bufferedSource ->
        val totalBytes = bufferedSource.readInt()
        publicSuffixListBytes = bufferedSource.readByteString(totalBytes.toLong())

        val totalExceptionBytes = bufferedSource.readInt()
        publicSuffixExceptionListBytes = bufferedSource.readByteString(totalExceptionBytes.toLong())
      }

      synchronized(this) {
        this.bytes = publicSuffixListBytes!!
        this.exceptionBytes = publicSuffixExceptionListBytes!!
      }
    } finally {
      readCompleteLatch.countDown()
    }
  }

  override fun ensureLoaded() {
    if (!listRead.get() && listRead.compareAndSet(false, true)) {
      readTheListUninterruptibly()
    } else {
      try {
        readCompleteLatch.await()
      } catch (_: InterruptedException) {
        Thread.currentThread().interrupt() // Retain interrupted status.
      }
    }

    check(::bytes.isInitialized) {
      // May have failed with an IOException
      "Unable to load $PUBLIC_SUFFIX_RESOURCE resource from the classpath."
    }
  }

  /**
   * Reads the public suffix list treating the operation as uninterruptible. We always want to read
   * the list otherwise we'll be left in a bad state. If the thread was interrupted prior to this
   * operation, it will be re-interrupted after the list is read.
   */
  private fun readTheListUninterruptibly() {
    var interrupted = false
    try {
      while (true) {
        try {
          readTheList()
          return
        } catch (_: InterruptedIOException) {
          Thread.interrupted() // Temporarily clear the interrupted state.
          interrupted = true
        } catch (e: IOException) {
          Platform.get().log("Failed to read public suffix list", Platform.WARN, e)
          return
        }
      }
    } finally {
      if (interrupted) {
        Thread.currentThread().interrupt() // Retain interrupted status.
      }
    }
  }

  /** Visible for testing. */
  fun setListBytes(
    publicSuffixListBytes: ByteString,
    publicSuffixExceptionListBytes: ByteString,
  ) {
    this.bytes = publicSuffixListBytes
    this.exceptionBytes = publicSuffixExceptionListBytes
    listRead.set(true)
    readCompleteLatch.countDown()
  }

  companion object {
    @JvmField
    val PUBLIC_SUFFIX_RESOURCE =
      "okhttp3/internal/publicsuffix/${PublicSuffixDatabase::class.java.simpleName}.gz".toPath()
  }
}
