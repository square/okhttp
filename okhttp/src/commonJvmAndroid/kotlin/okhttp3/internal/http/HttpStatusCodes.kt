/*
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 */

package okhttp3.internal.http

// HTTP Status Codes not offered by HttpUrlConnection.
//
// https://datatracker.ietf.org/doc/html/rfc7231#page-47
//
// From https://github.com/apache/httpcomponents-core/blob/master/httpcore5/src/main/java/org/apache/hc/core5/http/HttpStatus.java

/** `100 Continue` (HTTP/1.1 - RFC 7231)  */
const val HTTP_CONTINUE = 100

/** `102 Processing` (WebDAV - RFC 2518)  */
const val HTTP_PROCESSING = 102

/** `103 Early Hints (Early Hints - RFC 8297)` */
const val HTTP_EARLY_HINTS = 103

/** `307 Temporary Redirect` (HTTP/1.1 - RFC 7231)  */
const val HTTP_TEMP_REDIRECT = 307

/** `308 Permanent Redirect` (HTTP/1.1 - RFC 7538)  */
const val HTTP_PERM_REDIRECT = 308

/** `421 Misdirected Request` (HTTP/2 - RFC 7540)  */
const val HTTP_MISDIRECTED_REQUEST = 421
