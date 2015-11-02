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
 * Services specific to the host device's network interface. Prefer this over {@link
 * InetAddress#getAllByName} to make code more testable.
 */
public interface Network {
  Network DEFAULT = new Network() {
    @Override public InetAddress[] resolveInetAddresses(String host) throws UnknownHostException {
      if (host == null) throw new UnknownHostException("host == null");
      InetAddress[] ips_arr = InetAddress.getAllByName(host);
      //Prefer IPv4 over IPv6 because IPv4 has higher chances to be available
      //That's important because we'll probably have to wait for timeout before trying the second IP
      ArrayList<InetAddress> ips_all = new ArrayList<InetAddress>(ips_arr.length);
      ArrayList<InetAddress> ips_6 = new ArrayList<InetAddress>(ips_arr.length);
      for(InetAddress ip : ips_arr) {
          if (ip instanceof Inet4Address)
              ips_all.add(ip);
          else
              ips_6.add(ip);
      }
      ips_all.addAll(ips_6);
      return ips_all.toArray(ips_arr);
    }
  };

  InetAddress[] resolveInetAddresses(String host) throws UnknownHostException;
}
