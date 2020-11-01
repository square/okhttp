package okhttp3.brotli.internal

import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.internal.http.promisesBody
import okio.GzipSource
import okio.buffer
import okio.source
import org.brotli.dec.BrotliInputStream

fun uncompress(response: Response): Response {
  if (!response.promisesBody()) {
    return response
  }
  val body = response.body ?: return response
  val encoding = response.header("Content-Encoding") ?: return response

  val decompressedSource = when {
    encoding.equals("br", ignoreCase = true) ->
      BrotliInputStream(body.source().inputStream()).source().buffer()
    encoding.equals("gzip", ignoreCase = true) ->
      GzipSource(body.source()).buffer()
    else -> return response
  }

  return response.newBuilder()
    .removeHeader("Content-Encoding")
    .removeHeader("Content-Length")
    .body(decompressedSource.asResponseBody(body.contentType(), -1))
    .build()
}
