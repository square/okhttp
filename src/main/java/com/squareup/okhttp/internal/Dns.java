/*
 * Copyright (C) 2012 Square, Inc.
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
 * Domain name service. Prefer this over {@link InetAddress#getAllByName} to
 * make code more testable.
 */
public interface Dns {
  Dns DEFAULT = new Dns() {
    @Override public InetAddress[] getAllByName(String host) throws UnknownHostException {
      return InetAddress.getAllByName(host);
    }
  };

  InetAddress[] getAllByName(String host) throws UnknownHostException;
}
