/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.squareup.okhttp.internal.http;

import com.squareup.okhttp.internal.Util;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.ProtocolException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The HTTP status and unparsed header fields of a single HTTP message. Values
 * are represented as uninterpreted strings; use {@link RequestHeaders} and
 * {@link ResponseHeaders} for interpreted headers. This class maintains the
 * order of the header fields within the HTTP message.
 *
 * <p>This class tracks fields line-by-line. A field with multiple comma-
 * separated values on the same line will be treated as a field with a single
 * value by this class. It is the caller's responsibility to detect and split
 * on commas if their field permits multiple values. This simplifies use of
 * single-valued fields whose values routinely contain commas, such as cookies
 * or dates.
 *
 * <p>This class trims whitespace from values. It never returns values with
 * leading or trailing whitespace.
 */
public final class RawHeaders {
  private static final Comparator<String> FIELD_NAME_COMPARATOR = new Comparator<String>() {
    // @FindBugsSuppressWarnings("ES_COMPARING_PARAMETER_STRING_WITH_EQ")
    @Override public int compare(String a, String b) {
      if (a == b) {
        return 0;
      } else if (a == null) {
        return -1;
      } else if (b == null) {
        return 1;
      } else {
        return String.CASE_INSENSITIVE_ORDER.compare(a, b);
      }
    }
  };

  private final List<String> namesAndValues = new ArrayList<String>(20);
  private String requestLine;
  private String statusLine;
  private int httpMinorVersion = 1;
  private int responseCode = -1;
  private String responseMessage;

  public RawHeaders() {
  }

  public RawHeaders(RawHeaders copyFrom) {
    namesAndValues.addAll(copyFrom.namesAndValues);
    requestLine = copyFrom.requestLine;
    statusLine = copyFrom.statusLine;
    httpMinorVersion = copyFrom.httpMinorVersion;
    responseCode = copyFrom.responseCode;
    responseMessage = copyFrom.responseMessage;
  }

  /** Sets the request line (like "GET / HTTP/1.1"). */
  public void setRequestLine(String requestLine) {
    requestLine = requestLine.trim();
    this.requestLine = requestLine;
  }

  /** Sets the response status line (like "HTTP/1.0 200 OK"). */
  public void setStatusLine(String statusLine) throws IOException {
    // H T T P / 1 . 1   2 0 0   T e m p o r a r y   R e d i r e c t
    // 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0
    if (this.responseMessage != null) {
      throw new IllegalStateException("statusLine is already set");
    }
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

  /**
   * @param method like "GET", "POST", "HEAD", etc.
   * @param path like "/foo/bar.html"
   * @param version like "HTTP/1.1"
   * @param host like "www.android.com:1234"
   * @param scheme like "https"
   */
  public void addSpdyRequestHeaders(String method, String path, String version, String host,
      String scheme) {
    // TODO: populate the statusLine for the client's benefit?
    add(":method", method);
    add(":scheme", scheme);
    add(":path", path);
    add(":version", version);
    add(":host", host);
  }

  public String getStatusLine() {
    return statusLine;
  }

  /**
   * Returns the status line's HTTP minor version. This returns 0 for HTTP/1.0
   * and 1 for HTTP/1.1. This returns 1 if the HTTP version is unknown.
   */
  public int getHttpMinorVersion() {
    return httpMinorVersion != -1 ? httpMinorVersion : 1;
  }

  /** Returns the HTTP status code or -1 if it is unknown. */
  public int getResponseCode() {
    return responseCode;
  }

  /** Returns the HTTP status message or null if it is unknown. */
  public String getResponseMessage() {
    return responseMessage;
  }

  /**
   * Add an HTTP header line containing a field name, a literal colon, and a
   * value. This works around empty header names and header names that start
   * with a colon (created by old broken SPDY versions of the response cache).
   */
  public void addLine(String line) {
    int index = line.indexOf(":", 1);
    if (index != -1) {
      addLenient(line.substring(0, index), line.substring(index + 1));
    } else if (line.startsWith(":")) {
      addLenient("", line.substring(1)); // Empty header name.
    } else {
      addLenient("", line); // No header name.
    }
  }

  /** Add a field with the specified value. */
  public void add(String fieldName, String value) {
    if (fieldName == null) throw new IllegalArgumentException("fieldname == null");
    if (value == null) throw new IllegalArgumentException("value == null");
    if (fieldName.length() == 0 || fieldName.indexOf('\0') != -1 || value.indexOf('\0') != -1) {
      throw new IllegalArgumentException("Unexpected header: " + fieldName + ": " + value);
    }
    addLenient(fieldName, value);
  }

  /**
   * Add a field with the specified value without any validation. Only
   * appropriate for headers from the remote peer.
   */
  private void addLenient(String fieldName, String value) {
    namesAndValues.add(fieldName);
    namesAndValues.add(value.trim());
  }

  public void removeAll(String fieldName) {
    for (int i = 0; i < namesAndValues.size(); i += 2) {
      if (fieldName.equalsIgnoreCase(namesAndValues.get(i))) {
        namesAndValues.remove(i); // field name
        namesAndValues.remove(i); // value
      }
    }
  }

  public void addAll(String fieldName, List<String> headerFields) {
    for (String value : headerFields) {
      add(fieldName, value);
    }
  }

  /**
   * Set a field with the specified value. If the field is not found, it is
   * added. If the field is found, the existing values are replaced.
   */
  public void set(String fieldName, String value) {
    removeAll(fieldName);
    add(fieldName, value);
  }

  /** Returns the number of field values. */
  public int length() {
    return namesAndValues.size() / 2;
  }

  /** Returns the field at {@code position} or null if that is out of range. */
  public String getFieldName(int index) {
    int fieldNameIndex = index * 2;
    if (fieldNameIndex < 0 || fieldNameIndex >= namesAndValues.size()) {
      return null;
    }
    return namesAndValues.get(fieldNameIndex);
  }

  /** Returns an immutable case-insensitive set of header names. */
  public Set<String> names() {
    TreeSet<String> result = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
    for (int i = 0; i < length(); i++) {
      result.add(getFieldName(i));
    }
    return Collections.unmodifiableSet(result);
  }

  /** Returns the value at {@code index} or null if that is out of range. */
  public String getValue(int index) {
    int valueIndex = index * 2 + 1;
    if (valueIndex < 0 || valueIndex >= namesAndValues.size()) {
      return null;
    }
    return namesAndValues.get(valueIndex);
  }

  /** Returns the last value corresponding to the specified field, or null. */
  public String get(String fieldName) {
    for (int i = namesAndValues.size() - 2; i >= 0; i -= 2) {
      if (fieldName.equalsIgnoreCase(namesAndValues.get(i))) {
        return namesAndValues.get(i + 1);
      }
    }
    return null;
  }

  /** Returns an immutable list of the header values for {@code name}. */
  public List<String> values(String name) {
    List<String> result = null;
    for (int i = 0; i < length(); i++) {
      if (name.equalsIgnoreCase(getFieldName(i))) {
        if (result == null) result = new ArrayList<String>(2);
        result.add(getValue(i));
      }
    }
    return result != null
        ? Collections.unmodifiableList(result)
        : Collections.<String>emptyList();
  }

  /** @param fieldNames a case-insensitive set of HTTP header field names. */
  public RawHeaders getAll(Set<String> fieldNames) {
    RawHeaders result = new RawHeaders();
    for (int i = 0; i < namesAndValues.size(); i += 2) {
      String fieldName = namesAndValues.get(i);
      if (fieldNames.contains(fieldName)) {
        result.add(fieldName, namesAndValues.get(i + 1));
      }
    }
    return result;
  }

  /** Returns bytes of a request header for sending on an HTTP transport. */
  public byte[] toBytes() throws UnsupportedEncodingException {
    StringBuilder result = new StringBuilder(256);
    result.append(requestLine).append("\r\n");
    for (int i = 0; i < namesAndValues.size(); i += 2) {
      result.append(namesAndValues.get(i))
          .append(": ")
          .append(namesAndValues.get(i + 1))
          .append("\r\n");
    }
    result.append("\r\n");
    return result.toString().getBytes("ISO-8859-1");
  }

  /** Parses bytes of a response header from an HTTP transport. */
  public static RawHeaders fromBytes(InputStream in) throws IOException {
    RawHeaders headers;
    do {
      headers = new RawHeaders();
      headers.setStatusLine(Util.readAsciiLine(in));
      readHeaders(in, headers);
    } while (headers.getResponseCode() == HttpEngine.HTTP_CONTINUE);
    return headers;
  }

  /** Reads headers or trailers into {@code out}. */
  public static void readHeaders(InputStream in, RawHeaders out) throws IOException {
    // parse the result headers until the first blank line
    String line;
    while ((line = Util.readAsciiLine(in)).length() != 0) {
      out.addLine(line);
    }
  }

  /**
   * Returns an immutable map containing each field to its list of values. The
   * status line is mapped to null.
   */
  public Map<String, List<String>> toMultimap(boolean response) {
    Map<String, List<String>> result = new TreeMap<String, List<String>>(FIELD_NAME_COMPARATOR);
    for (int i = 0; i < namesAndValues.size(); i += 2) {
      String fieldName = namesAndValues.get(i);
      String value = namesAndValues.get(i + 1);

      List<String> allValues = new ArrayList<String>();
      List<String> otherValues = result.get(fieldName);
      if (otherValues != null) {
        allValues.addAll(otherValues);
      }
      allValues.add(value);
      result.put(fieldName, Collections.unmodifiableList(allValues));
    }
    if (response && statusLine != null) {
      result.put(null, Collections.unmodifiableList(Collections.singletonList(statusLine)));
    } else if (requestLine != null) {
      result.put(null, Collections.unmodifiableList(Collections.singletonList(requestLine)));
    }
    return Collections.unmodifiableMap(result);
  }

  /**
   * Creates a new instance from the given map of fields to values. If
   * present, the null field's last element will be used to set the status
   * line.
   */
  public static RawHeaders fromMultimap(Map<String, List<String>> map, boolean response)
      throws IOException {
    if (!response) throw new UnsupportedOperationException();
    RawHeaders result = new RawHeaders();
    for (Entry<String, List<String>> entry : map.entrySet()) {
      String fieldName = entry.getKey();
      List<String> values = entry.getValue();
      if (fieldName != null) {
        for (String value : values) {
          result.addLenient(fieldName, value);
        }
      } else if (!values.isEmpty()) {
        result.setStatusLine(values.get(values.size() - 1));
      }
    }
    return result;
  }

  /**
   * Returns a list of alternating names and values. Names are all lower case.
   * No names are repeated. If any name has multiple values, they are
   * concatenated using "\0" as a delimiter.
   */
  public List<String> toNameValueBlock() {
    Set<String> names = new HashSet<String>();
    List<String> result = new ArrayList<String>();
    for (int i = 0; i < namesAndValues.size(); i += 2) {
      String name = namesAndValues.get(i).toLowerCase(Locale.US);
      String value = namesAndValues.get(i + 1);

      // Drop headers that are forbidden when layering HTTP over SPDY.
      if (name.equals("connection")
          || name.equals("host")
          || name.equals("keep-alive")
          || name.equals("proxy-connection")
          || name.equals("transfer-encoding")) {
        continue;
      }

      // If we haven't seen this name before, add the pair to the end of the list...
      if (names.add(name)) {
        result.add(name);
        result.add(value);
        continue;
      }

      // ...otherwise concatenate the existing values and this value.
      for (int j = 0; j < result.size(); j += 2) {
        if (name.equals(result.get(j))) {
          result.set(j + 1, result.get(j + 1) + "\0" + value);
          break;
        }
      }
    }
    return result;
  }

  /** Returns headers for a name value block containing a SPDY response. */
  public static RawHeaders fromNameValueBlock(List<String> nameValueBlock) throws IOException {
    if (nameValueBlock.size() % 2 != 0) {
      throw new IllegalArgumentException("Unexpected name value block: " + nameValueBlock);
    }
    String status = null;
    String version = null;
    RawHeaders result = new RawHeaders();
    for (int i = 0; i < nameValueBlock.size(); i += 2) {
      String name = nameValueBlock.get(i);
      String values = nameValueBlock.get(i + 1);
      for (int start = 0; start < values.length(); ) {
        int end = values.indexOf('\0', start);
        if (end == -1) {
          end = values.length();
        }
        String value = values.substring(start, end);
        if (":status".equals(name)) {
          status = value;
        } else if (":version".equals(name)) {
          version = value;
        } else {
          result.namesAndValues.add(name);
          result.namesAndValues.add(value);
        }
        start = end + 1;
      }
    }
    if (status == null) throw new ProtocolException("Expected ':status' header not present");
    if (version == null) throw new ProtocolException("Expected ':version' header not present");
    result.setStatusLine(version + " " + status);
    return result;
  }
}
