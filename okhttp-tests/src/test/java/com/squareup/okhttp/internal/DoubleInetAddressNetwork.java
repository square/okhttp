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
package com.squareup.okhttp.internal;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * A network that always resolves two IP addresses per host. Use this when testing route selection
 * fallbacks to guarantee that a fallback address is available.
 */
public class DoubleInetAddressNetwork implements Network {
  @Override public InetAddress[] resolveInetAddresses(String host) throws UnknownHostException {
    InetAddress[] allInetAddresses = Network.DEFAULT.resolveInetAddresses(host);
    return new InetAddress[] { allInetAddresses[0], allInetAddresses[0] };
  }
}
