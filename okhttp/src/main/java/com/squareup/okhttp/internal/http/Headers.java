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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The HTTP status and unparsed header fields of a single HTTP message. Values
 * are represented as uninterpreted strings; use {@code Request} and
 * {@code Response} for interpreted headers. This class maintains the
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
public final class Headers {
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

  private final List<String> namesAndValues;

  private Headers(Builder builder) {
    this.namesAndValues = Util.immutableList(builder.namesAndValues);
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
    return get(namesAndValues, fieldName);
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
  public Headers getAll(Set<String> fieldNames) {
    Builder result = new Builder();
    for (int i = 0; i < namesAndValues.size(); i += 2) {
      String fieldName = namesAndValues.get(i);
      if (fieldNames.contains(fieldName)) {
        result.add(fieldName, namesAndValues.get(i + 1));
      }
    }
    return result.build();
  }

  /**
   * Returns an immutable map containing each field to its list of values.
   *
   * @param valueForNullKey the request line for requests, or the status line
   *     for responses. If non-null, this value is mapped to the null key.
   */
  public Map<String, List<String>> toMultimap(String valueForNullKey) {
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
    if (valueForNullKey != null) {
      result.put(null, Collections.unmodifiableList(Collections.singletonList(valueForNullKey)));
    }
    return Collections.unmodifiableMap(result);
  }

  public Builder newBuilder() {
    Builder result = new Builder();
    result.namesAndValues.addAll(namesAndValues);
    return result;
  }

  private static String get(List<String> namesAndValues, String fieldName) {
    for (int i = namesAndValues.size() - 2; i >= 0; i -= 2) {
      if (fieldName.equalsIgnoreCase(namesAndValues.get(i))) {
        return namesAndValues.get(i + 1);
      }
    }
    return null;
  }

  public static class Builder {
    private final List<String> namesAndValues = new ArrayList<String>(20);

    /** Equivalent to {@code build().get(fieldName)}, but potentially faster. */
    public String get(String fieldName) {
      return Headers.get(namesAndValues, fieldName);
    }

    /**
     * Add an HTTP header line containing a field name, a literal colon, and a
     * value. This works around empty header names and header names that start
     * with a colon (created by old broken SPDY versions of the response cache).
     */
    public Builder addLine(String line) {
      int index = line.indexOf(":", 1);
      if (index != -1) {
        return addLenient(line.substring(0, index), line.substring(index + 1));
      } else if (line.startsWith(":")) {
        return addLenient("", line.substring(1)); // Empty header name.
      } else {
        return addLenient("", line); // No header name.
      }
    }

    /** Add a field with the specified value. */
    public Builder add(String fieldName, String value) {
      if (fieldName == null) throw new IllegalArgumentException("fieldname == null");
      if (value == null) throw new IllegalArgumentException("value == null");
      if (fieldName.length() == 0 || fieldName.indexOf('\0') != -1 || value.indexOf('\0') != -1) {
        throw new IllegalArgumentException("Unexpected header: " + fieldName + ": " + value);
      }
      return addLenient(fieldName, value);
    }

    /**
     * Add a field with the specified value without any validation. Only
     * appropriate for headers from the remote peer.
     */
    private Builder addLenient(String fieldName, String value) {
      namesAndValues.add(fieldName);
      namesAndValues.add(value.trim());
      return this;
    }

    public Builder removeAll(String fieldName) {
      for (int i = 0; i < namesAndValues.size(); i += 2) {
        if (fieldName.equalsIgnoreCase(namesAndValues.get(i))) {
          namesAndValues.remove(i); // field name
          namesAndValues.remove(i); // value
        }
      }
      return this;
    }

    /**
     * Set a field with the specified value. If the field is not found, it is
     * added. If the field is found, the existing values are replaced.
     */
    public Builder set(String fieldName, String value) {
      removeAll(fieldName);
      add(fieldName, value);
      return this;
    }

    /** Reads headers or trailers into {@code out}. */
    public Builder readHeaders(InputStream in) throws IOException {
      // parse the result headers until the first blank line
      for (String line; (line = Util.readAsciiLine(in)).length() != 0; ) {
        addLine(line);
      }
      return this;
    }

    public Headers build() {
      return new Headers(this);
    }
  }
}
