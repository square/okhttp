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
package okhttp3.internal.http;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Date;
import java.util.TimeZone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class HttpDateTest {

  private TimeZone originalDefault;

  @Before
  public void setUp() throws Exception {
    originalDefault = TimeZone.getDefault();
    // The default timezone should affect none of these tests: HTTP specified GMT, so we set it to
    // something else.
    TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
  }

  @After
  public void tearDown() throws Exception {
    TimeZone.setDefault(originalDefault);
  }

  @Test public void parseStandardFormats() throws Exception {
    // RFC 822, updated by RFC 1123 with GMT.
    assertEquals(0L, HttpDate.parse("Thu, 01 Jan 1970 00:00:00 GMT").getTime());
    assertEquals(1402057830000L, HttpDate.parse("Fri, 06 Jun 2014 12:30:30 GMT").getTime());

    // RFC 850, obsoleted by RFC 1036 with GMT.
    assertEquals(0L, HttpDate.parse("Thursday, 01-Jan-70 00:00:00 GMT").getTime());
    assertEquals(1402057830000L, HttpDate.parse("Friday, 06-Jun-14 12:30:30 GMT").getTime());

    // ANSI C's asctime(): should use GMT, not platform default.
    assertEquals(0L, HttpDate.parse("Thu Jan 1 00:00:00 1970").getTime());
    assertEquals(1402057830000L, HttpDate.parse("Fri Jun 6 12:30:30 2014").getTime());
  }

  @Test public void format() throws Exception {
    assertEquals("Thu, 01 Jan 1970 00:00:00 GMT", HttpDate.format(new Date(0)));
    assertEquals("Fri, 06 Jun 2014 12:30:30 GMT", HttpDate.format(new Date(1402057830000L)));
  }

  @Test public void parseNonStandardStrings() throws Exception {
    // RFC 822, updated by RFC 1123 with any TZ
    assertEquals(3600000L, HttpDate.parse("Thu, 01 Jan 1970 00:00:00 GMT-01:00").getTime());
    assertEquals(28800000L, HttpDate.parse("Thu, 01 Jan 1970 00:00:00 PST").getTime());
    // Ignore trailing junk
    assertEquals(0L, HttpDate.parse("Thu, 01 Jan 1970 00:00:00 GMT JUNK").getTime());
    // Missing timezones treated as bad.
    assertNull(HttpDate.parse("Thu, 01 Jan 1970 00:00:00"));
    // Missing seconds treated as bad.
    assertNull(HttpDate.parse("Thu, 01 Jan 1970 00:00 GMT"));
    // Extra spaces treated as bad.
    assertNull(HttpDate.parse("Thu,  01 Jan 1970 00:00 GMT"));
    // Missing leading zero treated as bad.
    assertNull(HttpDate.parse("Thu, 1 Jan 1970 00:00 GMT"));

    // RFC 850, obsoleted by RFC 1036 with any TZ.
    assertEquals(3600000L, HttpDate.parse("Thursday, 01-Jan-1970 00:00:00 GMT-01:00").getTime());
    assertEquals(28800000L, HttpDate.parse("Thursday, 01-Jan-1970 00:00:00 PST").getTime());
    // Ignore trailing junk
    assertEquals(28800000L, HttpDate.parse("Thursday, 01-Jan-1970 00:00:00 PST JUNK").getTime());

    // ANSI C's asctime() format
    // This format ignores the timezone entirely even if it is present and uses GMT.
    assertEquals(1402057830000L, HttpDate.parse("Fri Jun 6 12:30:30 2014 PST").getTime());
    // Ignore trailing junk.
    assertEquals(1402057830000L, HttpDate.parse("Fri Jun 6 12:30:30 2014 JUNK").getTime());
  }
}
