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

package libcore.util;

/**
 * Exploits a weakness in the runtime to throw an arbitrary throwable without
 * the traditional declaration. <strong>This is a dangerous API that should be
 * used with great caution.</strong> Typically this is useful when rethrowing
 * throwables that are of a known range of types.
 *
 * <p>The following code must enumerate several types to rethrow:
 * <pre>
 * public void close() throws IOException {
 *     Throwable thrown = null;
 *     ...
 *
 *     if (thrown != null) {
 *         if (thrown instanceof IOException) {
 *             throw (IOException) thrown;
 *         } else if (thrown instanceof RuntimeException) {
 *             throw (RuntimeException) thrown;
 *         } else if (thrown instanceof Error) {
 *             throw (Error) thrown;
 *         } else {
 *             throw new AssertionError();
 *         }
 *     }
 * }</pre>
 * With SneakyThrow, rethrowing is easier:
 * <pre>
 * public void close() throws IOException {
 *     Throwable thrown = null;
 *     ...
 *
 *     if (thrown != null) {
 *         SneakyThrow.sneakyThrow(thrown);
 *     }
 * }</pre>
 */
public final class SneakyThrow {
    private SneakyThrow() {}

    public static void sneakyThrow(Throwable t) {
        SneakyThrow.<Error>sneakyThrow2(t);
    }

    /**
     * Exploits unsafety to throw an exception that the compiler wouldn't permit
     * but that the runtime doesn't check. See Java Puzzlers #43.
     */
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneakyThrow2(Throwable t) throws T {
        throw (T) t;
    }
}
