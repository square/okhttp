/*
 * Copyright (c) 2022 Square, Inc.
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
 *
 */

package okhttp3.internal.http

/**
 * The response codes for HTTP, as of version 1.1.
 */

/**
 * HTTP Status-Code 200: OK.
 */
const val HTTP_OK = 200

/**
 * HTTP Status-Code 201: Created.
 */
const val HTTP_CREATED = 201

/**
 * HTTP Status-Code 202: Accepted.
 */
const val HTTP_ACCEPTED = 202

/**
 * HTTP Status-Code 203: Non-Authoritative Information.
 */
const val HTTP_NOT_AUTHORITATIVE = 203

/**
 * HTTP Status-Code 204: No Content.
 */
const val HTTP_NO_CONTENT = 204

/**
 * HTTP Status-Code 205: Reset Content.
 */
const val HTTP_RESET = 205

/**
 * HTTP Status-Code 206: Partial Content.
 */
const val HTTP_PARTIAL = 206

/* 3XX: relocation/redirect */

/* 3XX: relocation/redirect */
/**
 * HTTP Status-Code 300: Multiple Choices.
 */
const val HTTP_MULT_CHOICE = 300

/**
 * HTTP Status-Code 301: Moved Permanently.
 */
const val HTTP_MOVED_PERM = 301

/**
 * HTTP Status-Code 302: Temporary Redirect.
 */
const val HTTP_MOVED_TEMP = 302

/**
 * HTTP Status-Code 303: See Other.
 */
const val HTTP_SEE_OTHER = 303

/**
 * HTTP Status-Code 304: Not Modified.
 */
const val HTTP_NOT_MODIFIED = 304

/**
 * HTTP Status-Code 305: Use Proxy.
 */
const val HTTP_USE_PROXY = 305

/* 4XX: client error */

/* 4XX: client error */
/**
 * HTTP Status-Code 400: Bad Request.
 */
const val HTTP_BAD_REQUEST = 400

/**
 * HTTP Status-Code 401: Unauthorized.
 */
const val HTTP_UNAUTHORIZED = 401

/**
 * HTTP Status-Code 402: Payment Required.
 */
const val HTTP_PAYMENT_REQUIRED = 402

/**
 * HTTP Status-Code 403: Forbidden.
 */
const val HTTP_FORBIDDEN = 403

/**
 * HTTP Status-Code 404: Not Found.
 */
const val HTTP_NOT_FOUND = 404

/**
 * HTTP Status-Code 405: Method Not Allowed.
 */
const val HTTP_BAD_METHOD = 405

/**
 * HTTP Status-Code 406: Not Acceptable.
 */
const val HTTP_NOT_ACCEPTABLE = 406

/**
 * HTTP Status-Code 407: Proxy Authentication Required.
 */
const val HTTP_PROXY_AUTH = 407

/**
 * HTTP Status-Code 408: Request Time-Out.
 */
const val HTTP_CLIENT_TIMEOUT = 408

/**
 * HTTP Status-Code 409: Conflict.
 */
const val HTTP_CONFLICT = 409

/**
 * HTTP Status-Code 410: Gone.
 */
const val HTTP_GONE = 410

/**
 * HTTP Status-Code 411: Length Required.
 */
const val HTTP_LENGTH_REQUIRED = 411

/**
 * HTTP Status-Code 412: Precondition Failed.
 */
const val HTTP_PRECON_FAILED = 412

/**
 * HTTP Status-Code 413: Request Entity Too Large.
 */
const val HTTP_ENTITY_TOO_LARGE = 413

/**
 * HTTP Status-Code 414: Request-URI Too Large.
 */
const val HTTP_REQ_TOO_LONG = 414

/**
 * HTTP Status-Code 415: Unsupported Media Type.
 */
const val HTTP_UNSUPPORTED_TYPE = 415

/**
 * HTTP Status-Code 500: Internal Server Error.
 */
const val HTTP_INTERNAL_ERROR = 500

/**
 * HTTP Status-Code 501: Not Implemented.
 */
const val HTTP_NOT_IMPLEMENTED = 501

/**
 * HTTP Status-Code 502: Bad Gateway.
 */
const val HTTP_BAD_GATEWAY = 502

/**
 * HTTP Status-Code 503: Service Unavailable.
 */
const val HTTP_UNAVAILABLE = 503

/**
 * HTTP Status-Code 504: Gateway Timeout.
 */
const val HTTP_GATEWAY_TIMEOUT = 504

/**
 * HTTP Status-Code 505: HTTP Version Not Supported.
 */
const val HTTP_VERSION = 505
