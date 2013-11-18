package com.squareup.okhttp.internal.spdy;

import java.io.IOException;

/** Indicates a compatibility or framing problem. */
class ProtocolException extends IOException {
  final ErrorCode errorCode;

  ProtocolException(ErrorCode errorCode) {
    super(errorCode.name());
    this.errorCode = errorCode;
  }
}
