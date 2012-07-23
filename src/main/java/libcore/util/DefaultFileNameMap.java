/*
 * Copyright (C) 2010 The Android Open Source Project
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

import java.net.FileNameMap;
import java.util.Locale;
import libcore.net.MimeUtils;

/**
 * Implements {@link java.net.FileNameMap} in terms of {@link libcore.net.MimeUtils}.
 */
class DefaultFileNameMap implements FileNameMap {
    public String getContentTypeFor(String filename) {
        if (filename.endsWith("/")) {
            // a directory, return html
            return MimeUtils.guessMimeTypeFromExtension("html");
        }
        int lastCharInExtension = filename.lastIndexOf('#');
        if (lastCharInExtension < 0) {
            lastCharInExtension = filename.length();
        }
        int firstCharInExtension = filename.lastIndexOf('.') + 1;
        String ext = "";
        if (firstCharInExtension > filename.lastIndexOf('/')) {
            ext = filename.substring(firstCharInExtension, lastCharInExtension);
        }
        return MimeUtils.guessMimeTypeFromExtension(ext.toLowerCase(Locale.US));
    }
}
