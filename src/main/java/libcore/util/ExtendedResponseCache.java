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

package libcore.util;

import java.io.IOException;
import java.net.CacheResponse;
import java.net.HttpURLConnection;

/**
 * A response cache that supports statistics tracking and updating stored
 * responses. Implementations of {@link java.net.ResponseCache} should implement this
 * interface to receive additional support from the HTTP engine.
 *
 * @hide
 */
public interface ExtendedResponseCache {

    /*
     * This hidden interface is defined in a non-hidden package (java.net) so
     * its @hide tag will be parsed by Doclava. This hides this interface from
     * implementing classes' documentation.
     */

    /**
     * Track an HTTP response being satisfied by {@code source}.
     * @hide
     */
    void trackResponse(ResponseSource source);

    /**
     * Track an conditional GET that was satisfied by this cache.
     * @hide
     */
    void trackConditionalCacheHit();

    /**
     * Updates stored HTTP headers using a hit on a conditional GET.
     * @hide
     */
    void update(CacheResponse conditionalCacheHit, HttpURLConnection httpConnection)
            throws IOException;
}
