package okhttp3.internal.http1

import okio.BufferedSink
import okio.BufferedSource

// TODO: Consider renaming this interface to `Http1Streams` or similar
//  to avoid confusion with `Stream` in mockwebserver3?
interface Streams {
  val source: BufferedSource
  val sink: BufferedSink

  fun cancel()
}
