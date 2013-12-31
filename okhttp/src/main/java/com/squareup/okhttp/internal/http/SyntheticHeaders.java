package com.squareup.okhttp.internal.http;

import com.squareup.okhttp.internal.Platform;

/** Headers added to the HTTP response for internal use by OkHttp. */
public final class SyntheticHeaders {
  static final String PREFIX = Platform.get().getPrefix();

  /** The local time when the request was sent. */
  public static final String SENT_MILLIS = PREFIX + "-Sent-Millis";

  /** The local time when the response was received. */
  public static final String RECEIVED_MILLIS = PREFIX + "-Received-Millis";

  /** The response source. */
  public static final String RESPONSE_SOURCE = PREFIX + "-Response-Source";

  /** The selected transport (spdy/3, http/1.1, etc). */
  public static final String SELECTED_TRANSPORT = PREFIX + "-Selected-Transport";

  private SyntheticHeaders() {
  }
}
