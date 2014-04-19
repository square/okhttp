package com.squareup.okhttp.internal.http;

import com.squareup.okhttp.Protocol;
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

    // Parse protocol like "HTTP/1.1" followed by a space.
    int codeStart;
    int httpMinorVersion;
    if (statusLine.startsWith("HTTP/1.")) {
      if (statusLine.length() < 9 || statusLine.charAt(8) != ' ') {
        throw new ProtocolException("Unexpected status line: " + statusLine);
      }
      httpMinorVersion = statusLine.charAt(7) - '0';
      codeStart = 9;
      if (httpMinorVersion < 0 || httpMinorVersion > 9) {
        throw new ProtocolException("Unexpected status line: " + statusLine);
      }
    } else if (statusLine.startsWith("ICY ")) {
      // Shoutcast uses ICY instead of "HTTP/1.0".
      httpMinorVersion = 0;
      codeStart = 4;
    } else {
      throw new ProtocolException("Unexpected status line: " + statusLine);
    }

    // Parse response code like "200". Always 3 digits.
    if (statusLine.length() < codeStart + 3) {
      throw new ProtocolException("Unexpected status line: " + statusLine);
    }
    int responseCode;
    try {
      responseCode = Integer.parseInt(statusLine.substring(codeStart, codeStart + 3));
    } catch (NumberFormatException e) {
      throw new ProtocolException("Unexpected status line: " + statusLine);
    }

    // Parse an optional response message like "OK" or "Not Modified". If it
    // exists, it is separated from the response code by a space.
    String responseMessage = "";
    if (statusLine.length() > codeStart + 3) {
      if (statusLine.charAt(codeStart + 3) != ' ') {
        throw new ProtocolException("Unexpected status line: " + statusLine);
      }
      responseMessage = statusLine.substring(codeStart + 4);
    }

    this.responseMessage = responseMessage;
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

  public Protocol getProtocol() {
    return httpMinorVersion == 0 ? Protocol.HTTP_1_0 : Protocol.HTTP_1_1;
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
