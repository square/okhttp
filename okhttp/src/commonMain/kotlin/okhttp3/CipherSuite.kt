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
package okhttp3

/**
 * [TLS cipher suites][iana_tls_parameters].
 *
 * **Not all cipher suites are supported on all platforms.** As newer cipher suites are created (for
 * stronger privacy, better performance, etc.) they will be adopted by the platform and then exposed
 * here.
 *
 * [iana_tls_parameters]: https://www.iana.org/assignments/tls-parameters/tls-parameters.xhtml
 */
expect class CipherSuite
