/*
 * Copyright (C) 2014 Square, Inc.
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
package okhttp3.internal.spdy.hpackjson;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import okhttp3.internal.framed.Header;
import okio.ByteString;

/**
 * Representation of an individual case (set of headers and wire format). There are many cases for a
 * single story.  This class is used reflectively with Gson to parse stories.
 */
public class Case implements Cloneable {

  private int seqno;
  private String wire;
  private List<Map<String, String>> headers;

  public List<Header> getHeaders() {
    List<Header> result = new ArrayList<>();
    for (Map<String, String> inputHeader : headers) {
      Map.Entry<String, String> entry = inputHeader.entrySet().iterator().next();
      result.add(new Header(entry.getKey(), entry.getValue()));
    }
    return result;
  }

  public ByteString getWire() {
    return ByteString.decodeHex(wire);
  }

  public int getSeqno() {
    return seqno;
  }

  public void setWire(ByteString wire) {
    this.wire = wire.hex();
  }

  @Override
  protected Case clone() throws CloneNotSupportedException {
    Case result = new Case();
    result.seqno = seqno;
    result.wire = wire;
    result.headers = new ArrayList<>();
    for (Map<String, String> header : headers) {
      result.headers.add(new LinkedHashMap<String, String>(header));
    }
    return result;
  }
}
