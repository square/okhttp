package com.squareup.okhttp.internal.http;

import java.io.IOException;
import java.net.ProtocolException;

public final class StatusLine {
  /** Numeric status code, 307: Temporary Redirect. */
  public static final int HTTP_TEMP_REDIRECT = 307;
  public static final int HTTP_CONTINUE = 100;

  private final String statusLine;
  private final int httpMinorVersion;
  private final int responseCode;
  private final String responseMessage;

  /** Sets the response status line (like "HTTP/1.0 200 OK"). */
  public StatusLine(String statusLine) throws IOException {
    // H T T P / 1 . 1   2 0 0   T e m p o r a r y   R e d i r e c t
    // 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0

    // We allow empty message without leading white space since some servers
    // do not send the white space when the message is empty.
    boolean hasMessage = statusLine.length() > 13;
    if (!statusLine.startsWith("HTTP/1.")
        || statusLine.length() < 12
        || statusLine.charAt(8) != ' '
        || (hasMessage && statusLine.charAt(12) != ' ')) {
      throw new ProtocolException("Unexpected status line: " + statusLine);
    }
    int httpMinorVersion = statusLine.charAt(7) - '0';
    if (httpMinorVersion < 0 || httpMinorVersion > 9) {
      throw new ProtocolException("Unexpected status line: " + statusLine);
    }
    int responseCode;
    try {
      responseCode = Integer.parseInt(statusLine.substring(9, 12));
    } catch (NumberFormatException e) {
      throw new ProtocolException("Unexpected status line: " + statusLine);
    }
    this.responseMessage = hasMessage ? statusLine.substring(13) : "";
    this.responseCode = responseCode;
    this.statusLine = statusLine;
    this.httpMinorVersion = httpMinorVersion;
  }

  public String getStatusLine() {
    return statusLine;
  }

  /**
   * Returns the status line's HTTP minor version. This returns 0 for HTTP/1.0
   * and 1 for HTTP/1.1. This returns 1 if the HTTP version is unknown.
   */
  public int httpMinorVersion() {
    return httpMinorVersion != -1 ? httpMinorVersion : 1;
  }

  /** Returns the HTTP status code or -1 if it is unknown. */
  public int code() {
    return responseCode;
  }

  /** Returns the HTTP status message or null if it is unknown. */
  public String message() {
    return responseMessage;
  }

}
