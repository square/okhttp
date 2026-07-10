/*
 * Copyright (c) 2026 OkHttp Authors
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
package okhttp3.dns

class Dns2Test {

  /**
   * Should ignore SvcParams and recurse on targetName.
   */
  fun `svcb alias result`() {
  }

  /**
   * Should include the port in the DNS query
   */
  fun `svcb non default port`() {
  }

  /**
   * Same as no DNS results.
   */
  fun `svcb alias result with dot`() {
  }

  /**
   * Should recurse on each alternate service
   */
  fun `svcb service mode result with no parameters`() {
  }

  /**
   * Should be shuffled, then sorted by SvcPriority.
   */
  fun `svcb service mode result sorting`() {
  }

  /**
   * Should emit the [DnsRecord.Svcb] record
   */
  fun `svcb service mode result with dot`() {
  }

  /**
   * Should recurse on each alternate service, and emit the [DnsRecord.Svcb] record
   */
  fun `svcb service mode result with parameters`() {
  }

  /**
   * What do we do a pair of bindings mutually recurse
   */
  fun `svcb service mode loop`() {
  }

  /**
   * What do we do a pair of bindings mutually recurse
   */
  fun `svcb alias mode loop`() {
  }

  /**
   * Should be composed with the default value.
   */
  fun `svcb alpn ids`() {
  }

  /**
   * Should be composed without the default value.
   */
  fun `svcb alpn ids and no default alpn`() {
  }

  /** This is an error case. */
  fun `svcb no default alpn without alpn ids`() {
  }

  /** Success. */
  fun `svcb mandatory parameters are recognized`() {
  }

  /** Ignore the entire record. */
  fun `svcb mandatory parameters are not unrecognized`() {
  }

  fun `svcb port`() {
  }

  fun `svcb port ipv4 address hints`() {
  }

  fun `svcb port ipv6 address hints`() {
  }

  fun `svcb ech config list`() {
  }
}
