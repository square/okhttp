/*
 * Copyright (C) 2012 The Android Open Source Project
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

package libcore.net.http;

import junit.framework.TestCase;

public final class NewURLConnectionTest extends TestCase {

    public void testUrlConnection() {
    }

    // TODO: write a test that shows pooled connections detect HTTP/1.0 (vs. HTTP/1.1)

    // TODO: write a test that shows POST bodies are retained on AUTH problems (or prove it unnecessary)

    // TODO: cookies + trailers. Do cookie headers get processed too many times?

    // TODO: crash on header names or values containing the '\0' character

    // TODO: crash on empty names and empty values

    // TODO: deflate compression

    // TODO: read the outgoing status line and incoming status line?

}
