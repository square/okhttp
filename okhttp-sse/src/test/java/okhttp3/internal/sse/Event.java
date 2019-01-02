/*
 * Copyright (C) 2018 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okhttp3.internal.sse;

import java.util.Objects;
import javax.annotation.Nullable;

final class Event {
  final @Nullable String id;
  final @Nullable String type;
  final String data;

  Event(@Nullable String id, @Nullable String type, String data) {
    if (data == null) throw new NullPointerException("data == null");
    this.id = id;
    this.type = type;
    this.data = data;
  }

  @Override public String toString() {
    return "Event{id='" + id + "', type='" + type + "', data='" + data + "'}";
  }

  @Override public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof Event)) return false;
    Event other = (Event) o;
    return Objects.equals(id, other.id)
        && Objects.equals(type, other.type)
        && data.equals(other.data);
  }

  @Override public int hashCode() {
    int result = Objects.hashCode(id);
    result = 31 * result + Objects.hashCode(type);
    result = 31 * result + data.hashCode();
    return result;
  }
}
