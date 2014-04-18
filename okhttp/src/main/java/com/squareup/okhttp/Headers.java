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

package com.squareup.okhttp;

import com.squareup.okhttp.internal.http.HttpDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The header fields of a single HTTP message. Values are uninterpreted strings;
 * use {@code Request} and {@code Response} for interpreted headers. This class
 * maintains the order of the header fields within the HTTP message.
 *
 * <p>This class tracks header values line-by-line. A field with multiple comma-
 * separated values on the same line will be treated as a field with a single
 * value by this class. It is the caller's responsibility to detect and split
 * on commas if their field permits multiple values. This simplifies use of
 * single-valued fields whose values routinely contain commas, such as cookies
 * or dates.
 *
 * <p>This class trims whitespace from values. It never returns values with
 * leading or trailing whitespace.
 *
 * <p>Instances of this class are immutable. Use {@link Builder} to create
 * instances.
 */
public final class Headers {
  private final String[] namesAndValues;

  private Headers(Builder builder) {
    this.namesAndValues = builder.namesAndValues.toArray(new String[builder.namesAndValues.size()]);
  }

  /** Returns the last value corresponding to the specified field, or null. */
  public String get(String fieldName) {
    return get(namesAndValues, fieldName);
  }

  /**
   * Returns the last value corresponding to the specified field parsed as an
   * HTTP date, or null if either the field is absent or cannot be parsed as a
   * date.
   */
  public Date getDate(String fieldName) {
    String value = get(fieldName);
    return value != null ? HttpDate.parse(value) : null;
  }

  /** Returns the number of field values. */
  public int size() {
    return namesAndValues.length / 2;
  }

  /** Returns the field at {@code position} or null if that is out of range. */
  public String name(int index) {
    int fieldNameIndex = index * 2;
    if (fieldNameIndex < 0 || fieldNameIndex >= namesAndValues.length) {
      return null;
    }
    return namesAndValues[fieldNameIndex];
  }

  /** Returns the value at {@code index} or null if that is out of range. */
  public String value(int index) {
    int valueIndex = index * 2 + 1;
    if (valueIndex < 0 || valueIndex >= namesAndValues.length) {
      return null;
    }
    return namesAndValues[valueIndex];
  }

  /** Returns an immutable case-insensitive set of header names. */
  public Set<String> names() {
    TreeSet<String> result = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
    for (int i = 0; i < size(); i++) {
      result.add(name(i));
    }
    return Collections.unmodifiableSet(result);
  }

  /** Returns an immutable list of the header values for {@code name}. */
  public List<String> values(String name) {
    List<String> result = null;
    for (int i = 0; i < size(); i++) {
      if (name.equalsIgnoreCase(name(i))) {
        if (result == null) result = new ArrayList<String>(2);
        result.add(value(i));
      }
    }
    return result != null
        ? Collections.unmodifiableList(result)
        : Collections.<String>emptyList();
  }

  public Builder newBuilder() {
    Builder result = new Builder();
    result.namesAndValues.addAll(Arrays.asList(namesAndValues));
    return result;
  }

  @Override public String toString() {
    StringBuilder result = new StringBuilder();
    for (int i = 0; i < size(); i++) {
      result.append(name(i)).append(": ").append(value(i)).append("\n");
    }
    return result.toString();
  }

  private static String get(String[] namesAndValues, String fieldName) {
    for (int i = namesAndValues.length - 2; i >= 0; i -= 2) {
      if (fieldName.equalsIgnoreCase(namesAndValues[i])) {
        return namesAndValues[i + 1];
      }
    }
    return null;
  }

  public static class Builder {
    private final List<String> namesAndValues = new ArrayList<String>(20);

    /** Add an header line containing a field name, a literal colon, and a value. */
    public Builder addLine(String line) {
      int index = line.indexOf(":", 1);
      if (index != -1) {
        return addLenient(line.substring(0, index), line.substring(index + 1));
      } else if (line.startsWith(":")) {
        // Work around empty header names and header names that start with a
        // colon (created by old broken SPDY versions of the response cache).
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

    /** Equivalent to {@code build().get(fieldName)}, but potentially faster. */
    public String get(String fieldName) {
      for (int i = namesAndValues.size() - 2; i >= 0; i -= 2) {
        if (fieldName.equalsIgnoreCase(namesAndValues.get(i))) {
          return namesAndValues.get(i + 1);
        }
      }
      return null;
    }

    public Headers build() {
      return new Headers(this);
    }
  }
}
