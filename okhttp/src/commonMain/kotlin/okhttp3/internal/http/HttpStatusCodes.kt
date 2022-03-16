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

/**
 * HTTP Status Codes
 *
 * https://datatracker.ietf.org/doc/html/rfc7231#page-47
 *
 * From https://github.com/apache/httpcomponents-core/blob/master/httpcore5/src/main/java/org/apache/hc/core5/http/HttpStatus.java
 */

// --- 1xx Informational ---
// /** `100 1xx Informational` (HTTP/1.1 - RFC 7231)  */
// const val HTTP_INFORMATIONAL = 100

/** `100 Continue` (HTTP/1.1 - RFC 7231)  */
const val HTTP_CONTINUE = 100

/** `101 Switching Protocols` (HTTP/1.1 - RFC 7231) */
const val HTTP_SWITCHING_PROTOCOLS = 101

/** `102 Processing` (WebDAV - RFC 2518)  */
const val HTTP_PROCESSING = 102

/** `103 Early Hints (Early Hints - RFC 8297)` */
const val HTTP_EARLY_HINTS = 103

// --- 2xx Success ---
// /** `2xx Success` (HTTP/1.0 - RFC 7231)  */
// const val HTTP_SUCCESS = 200

/** `200 OK` (HTTP/1.0 - RFC 7231)  */
const val HTTP_OK = 200

/** `201 Created` (HTTP/1.0 - RFC 7231)  */
const val HTTP_CREATED = 201

/** `202 Accepted` (HTTP/1.0 - RFC 7231)  */
const val HTTP_ACCEPTED = 202

/** `203 Non Authoritative Information` (HTTP/1.1 - RFC 7231)  */
const val HTTP_NOT_AUTHORITATIVE = 203

/** `204 No Content` (HTTP/1.0 - RFC 7231)  */
const val HTTP_NO_CONTENT = 204

/** `205 Reset Content` (HTTP/1.1 - RFC 7231)  */
const val HTTP_RESET_CONTENT = 205

/** `206 Partial Content` (HTTP/1.1 - RFC 7231)  */
const val HTTP_PARTIAL_CONTENT = 206

/**
 * `207 Multi-Status` (WebDAV - RFC 2518)
 * or
 * `207 Partial Update OK` (HTTP/1.1 - draft-ietf-http-v11-spec-rev-01?)
 */
const val HTTP_MULTI_STATUS = 207

/**
 * `208 Already Reported` (WebDAV - RFC 5842, p.30, section 7.1)
 */
const val HTTP_ALREADY_REPORTED = 208

/**
 * `226 IM Used` (Delta encoding in HTTP - RFC 3229, p. 30, section 10.4.1)
 */
const val HTTP_IM_USED = 226

// --- 3xx Redirection ---
// /** `3xx Redirection` (HTTP/1.1 - RFC 7231)  */
// const val HTTP_REDIRECTION = 300

/** `300 Multiple Choices` (HTTP/1.1 - RFC 7231)  */
const val HTTP_MULT_CHOICE = 300

/** `301 Moved Permanently` (HTTP/1.0 - RFC 7231)  */
const val HTTP_MOVED_PERM = 301

/** `302 Moved Temporarily` (Sometimes `Found`) (HTTP/1.0 - RFC 7231)  */
const val HTTP_MOVED_TEMP = 302

/** `303 See Other` (HTTP/1.1 - RFC 7231)  */
const val HTTP_SEE_OTHER = 303

/** `304 Not Modified` (HTTP/1.0 - RFC 7231)  */
const val HTTP_NOT_MODIFIED = 304

/** `305 Use Proxy` (HTTP/1.1 - RFC 7231)  */
const val HTTP_USE_PROXY = 305

/** `307 Temporary Redirect` (HTTP/1.1 - RFC 7231)  */
const val HTTP_TEMP_REDIRECT = 307

/** `308 Permanent Redirect` (HTTP/1.1 - RFC 7538)  */
const val HTTP_PERM_REDIRECT = 308

// --- 4xx Client Error ---
// /** `4xx Client Error` (HTTP/1.1 - RFC 7231)  */
// const val HTTP_CLIENT_ERROR = 400

/** `400 Bad Request` (HTTP/1.1 - RFC 7231)  */
const val HTTP_BAD_REQUEST = 400

/** `401 Unauthorized` (HTTP/1.0 - RFC 7231)  */
const val HTTP_UNAUTHORIZED = 401

/** `402 Payment Required` (HTTP/1.1 - RFC 7231)  */
const val HTTP_PAYMENT_REQUIRED = 402

/** `403 Forbidden` (HTTP/1.0 - RFC 7231)  */
const val HTTP_FORBIDDEN = 403

/** `404 Not Found` (HTTP/1.0 - RFC 7231)  */
const val HTTP_NOT_FOUND = 404

/** `405 Method Not Allowed` (HTTP/1.1 - RFC 7231)  */
const val HTTP_BAD_METHOD = 405

/** `406 Not Acceptable` (HTTP/1.1 - RFC 7231)  */
const val HTTP_NOT_ACCEPTABLE = 406

/** `407 Proxy Authentication Required` (HTTP/1.1 - RFC 7231) */
const val HTTP_PROXY_AUTH = 407

/** `408 Request Timeout` (HTTP/1.1 - RFC 7231)  */
const val HTTP_CLIENT_TIMEOUT = 408

/** `409 Conflict` (HTTP/1.1 - RFC 7231)  */
const val HTTP_CONFLICT = 409

/** `410 Gone` (HTTP/1.1 - RFC 7231)  */
const val HTTP_GONE = 410

/** `411 Length Required` (HTTP/1.1 - RFC 7231)  */
const val HTTP_LENGTH_REQUIRED = 411

/** `412 Precondition Failed` (HTTP/1.1 - RFC 7231)  */
const val HTTP_PRECONDITION_FAILED = 412

/** `413 Request Entity Too Large` (HTTP/1.1 - RFC 7231)  */
const val HTTP_REQUEST_TOO_LONG = 413

/** `414 Request-URI Too Long` (HTTP/1.1 - RFC 7231)  */
const val HTTP_REQ_TOO_LONG = 414

/** `415 Unsupported Media Type` (HTTP/1.1 - RFC 7231)  */
const val HTTP_UNSUPPORTED_MEDIA_TYPE = 415

/** `416 Requested Range Not Satisfiable` (HTTP/1.1 - RFC 7231)  */
const val HTTP_REQUESTED_RANGE_NOT_SATISFIABLE = 416

/** `417 Expectation Failed` (HTTP/1.1 - RFC 7231)  */
const val HTTP_EXPECTATION_FAILED = 417

/** `421 Misdirected Request` (HTTP/2 - RFC 7540)  */
const val HTTP_MISDIRECTED_REQUEST = 421

/**
 * Static constant for a 419 error.
 * `419 Insufficient Space on Resource`
 * (WebDAV - draft-ietf-webdav-protocol-05?)
 * or `419 Proxy Reauthentication Required`
 * (HTTP/1.1 drafts?)
 */
const val HTTP_INSUFFICIENT_SPACE_ON_RESOURCE = 419

/**
 * Static constant for a 420 error.
 * `420 Method Failure`
 * (WebDAV - draft-ietf-webdav-protocol-05?)
 */
const val HTTP_METHOD_FAILURE = 420

/** `422 Unprocessable Entity` (WebDAV - RFC 2518)  */
const val HTTP_UNPROCESSABLE_ENTITY = 422

/** `423 Locked` (WebDAV - RFC 2518)  */
const val HTTP_LOCKED = 423

/** `424 Failed Dependency` (WebDAV - RFC 2518)  */
const val HTTP_FAILED_DEPENDENCY = 424

/** `425 Too Early` (Using Early Data in HTTP - RFC 8470)  */
const val HTTP_TOO_EARLY = 425

/** `426 Upgrade Dependency` (HTTP/1.1 - RFC 2817)  */
const val HTTP_UPGRADE_REQUIRED = 426

/** `428 Precondition Required` (Additional HTTP Status Codes - RFC 6585)  */
const val HTTP_PRECONDITION_REQUIRED = 428

/** `429 Too Many Requests` (Additional HTTP Status Codes - RFC 6585)  */
const val HTTP_TOO_MANY_REQUESTS = 429

/** `431 Request Header Fields Too Large` (Additional HTTP Status Codes - RFC 6585)  */
const val HTTP_REQUEST_HEADER_FIELDS_TOO_LARGE = 431

/** `451 Unavailable For Legal Reasons` (Legal Obstacles - RFC 7725)  */
const val HTTP_UNAVAILABLE_FOR_LEGAL_REASONS = 451

// --- 5xx Server Error ---
// /** `500 Server Error` (HTTP/1.0 - RFC 7231)  */
// const val HTTP_SERVER_ERROR = 500

/** `500 Internal Server Error` (HTTP/1.0 - RFC 7231)  */
const val HTTP_INTERNAL_SERVER_ERROR = 500

/** `501 Not Implemented` (HTTP/1.0 - RFC 7231)  */
const val HTTP_NOT_IMPLEMENTED = 501

/** `502 Bad Gateway` (HTTP/1.0 - RFC 7231)  */
const val HTTP_BAD_GATEWAY = 502

/** `503 Service Unavailable` (HTTP/1.0 - RFC 7231)  */
const val HTTP_UNAVAILABLE = 503

/** `504 Gateway Timeout` (HTTP/1.1 - RFC 7231)  */
const val HTTP_GATEWAY_TIMEOUT = 504

/** `505 HTTP Version Not Supported` (HTTP/1.1 - RFC 7231)  */
const val HTTP_HTTP_VERSION_NOT_SUPPORTED = 505

/** `506 Variant Also Negotiates` ( Transparent Content Negotiation - RFC 2295)  */
const val HTTP_VARIANT_ALSO_NEGOTIATES = 506

/** `507 Insufficient Storage` (WebDAV - RFC 2518)  */
const val HTTP_INSUFFICIENT_STORAGE = 507

/**
 * `508 Loop Detected` (WebDAV - RFC 5842, p.33, section 7.2)
 */
const val HTTP_LOOP_DETECTED = 508

/**
 * `510 Not Extended` (An HTTP Extension Framework - RFC 2774, p. 10, section 7)
 */
const val HTTP_NOT_EXTENDED = 510

/** `511 Network Authentication Required` (Additional HTTP Status Codes - RFC 6585)  */
const val HTTP_NETWORK_AUTHENTICATION_REQUIRED = 511
