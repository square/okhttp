/*
 * Copyright (C) 2016 Google Inc.
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

package okhttp3.mockwebserver;

import static java.lang.System.currentTimeMillis;

public class WaitForCondition {
    public static final int POLLING_INTERVAL = 500;

    private WaitForCondition() { }

    /**
     * Waits until the condition is satisfied.
     * Polling interval is 500 millis
     */
    public static void waitFor(Condition condition, long maxWaitInMillis) {
        long startTime = currentTimeMillis();
        while (!condition.isSatisfied() && currentTimeMillis() - startTime < maxWaitInMillis) {
            try {
                Thread.sleep(POLLING_INTERVAL);
            } catch (InterruptedException ignored) {

            }
        }

        if (!condition.isSatisfied())
            throw new RuntimeException("Timed out waiting for condition to be satisfied!");
    }

    public interface Condition {
        boolean isSatisfied();
    }

}
