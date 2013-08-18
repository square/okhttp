package com.squareup.okhttp.internal.spdy;

public enum ErrorCode {
  /** Not an error! For SPDY stream resets, prefer null over NO_ERROR. */
  NO_ERROR(0, -1, 0),

  PROTOCOL_ERROR(1, 1, 1),

  /** A subtype of PROTOCOL_ERROR used by SPDY. */
  INVALID_STREAM(1, 2, -1),

  /** A subtype of PROTOCOL_ERROR used by SPDY. */
  UNSUPPORTED_VERSION(1, 4, -1),

  /** A subtype of PROTOCOL_ERROR used by SPDY. */
  STREAM_IN_USE(1, 8, -1),

  /** A subtype of PROTOCOL_ERROR used by SPDY. */
  STREAM_ALREADY_CLOSED(1, 9, -1),

  INTERNAL_ERROR(2, 6, 2),

  FLOW_CONTROL_ERROR(3, 7, -1),

  STREAM_CLOSED(5, -1, -1),

  FRAME_TOO_LARGE(6, 11, -1),

  REFUSED_STREAM(7, 3, -1),

  CANCEL(8, 5, -1),

  COMPRESSION_ERROR(9, -1, -1),

  INVALID_CREDENTIALS(-1, 10, -1);

  public final int httpCode;
  public final int spdyRstCode;
  public final int spdyGoAwayCode;

  private ErrorCode(int httpCode, int spdyRstCode, int spdyGoAwayCode) {
    this.httpCode = httpCode;
    this.spdyRstCode = spdyRstCode;
    this.spdyGoAwayCode = spdyGoAwayCode;
  }

  public static ErrorCode fromSpdy3Rst(int code) {
    for (ErrorCode errorCode : ErrorCode.values()) {
      if (errorCode.spdyRstCode == code) return errorCode;
    }
    return null;
  }

  public static ErrorCode fromHttp2(int code) {
    for (ErrorCode errorCode : ErrorCode.values()) {
      if (errorCode.httpCode == code) return errorCode;
    }
    return null;
  }

  public static ErrorCode fromSpdyGoAway(int code) {
    for (ErrorCode errorCode : ErrorCode.values()) {
      if (errorCode.spdyGoAwayCode == code) return errorCode;
    }
    return null;
  }
}
