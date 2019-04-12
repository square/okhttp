/*
 * Copyright (C) 2015 Square, Inc.
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

import okhttp3.internal.Util
import okhttp3.internal.Util.UTC
import okhttp3.internal.Util.canonicalizeHost
import okhttp3.internal.Util.delimiterOffset
import okhttp3.internal.Util.indexOfControlOrNonAscii
import okhttp3.internal.Util.trimSubstring
import okhttp3.internal.Util.verifyAsIpAddress
import okhttp3.internal.http.HttpDate
import okhttp3.internal.publicsuffix.PublicSuffixDatabase
import java.util.Calendar
import java.util.Collections
import java.util.Date
import java.util.GregorianCalendar
import java.util.Locale
import java.util.regex.Pattern

/**
 * An [RFC 6265](http://tools.ietf.org/html/rfc6265) Cookie.
 *
 * This class doesn't support additional attributes on cookies, like
 * [Chromium's Priority=HIGH extension][chromium_extension].
 *
 * [chromium_extension]: https://code.google.com/p/chromium/issues/detail?id=232693
 */
data class Cookie private constructor(
  private val name: String,
  private val value: String,
  private val expiresAt: Long,
  private val domain: String,
  private val path: String,
  private val secure: Boolean,
  private val httpOnly: Boolean,
  private val persistent: Boolean, // True if 'expires' or 'max-age' is present.
  private val hostOnly: Boolean // True unless 'domain' is present.
) {
  /** Returns a non-empty string with this cookie's name.  */
  fun name(): String = name

  /** Returns a possibly-empty string with this cookie's value.  */
  fun value(): String = value

  /** Returns true if this cookie does not expire at the end of the current session.  */
  fun persistent(): Boolean = persistent

  /**
   * Returns the time that this cookie expires, in the same format as [System.currentTimeMillis].
   * This is December 31, 9999 if the cookie is [persistent], in which case it will expire at the
   * end of the current session.
   *
   * This may return a value less than the current time, in which case the cookie is already
   * expired. Webservers may return expired cookies as a mechanism to delete previously set cookies
   * that may or may not themselves be expired.
   */
  fun expiresAt(): Long = expiresAt

  /**
   * Returns true if this cookie's domain should be interpreted as a single host name, or false if
   * it should be interpreted as a pattern. This flag will be false if its `Set-Cookie` header
   * included a `domain` attribute.
   *
   * For example, suppose the cookie's domain is `example.com`. If this flag is true it matches
   * **only** `example.com`. If this flag is false it matches `example.com` and all subdomains
   * including `api.example.com`, `www.example.com`, and `beta.api.example.com`.
   */
  fun hostOnly(): Boolean = hostOnly

  /**
   * Returns the cookie's domain. If [hostOnly] returns true this is the only domain that matches
   * this cookie; otherwise it matches this domain and all subdomains.
   */
  fun domain(): String = domain

  /**
   * Returns this cookie's path. This cookie matches URLs prefixed with path segments that match
   * this path's segments. For example, if this path is `/foo` this cookie matches requests to
   * `/foo` and `/foo/bar`, but not `/` or `/football`.
   */
  fun path(): String = path

  /**
   * Returns true if this cookie should be limited to only HTTP APIs. In web browsers this prevents
   * the cookie from being accessible to scripts.
   */
  fun httpOnly(): Boolean = httpOnly

  /** Returns true if this cookie should be limited to only HTTPS requests.  */
  fun secure(): Boolean = secure

  /**
   * Returns true if this cookie should be included on a request to `url`. In addition to this
   * check callers should also confirm that this cookie has not expired.
   */
  fun matches(url: HttpUrl): Boolean {
    val domainMatch = if (hostOnly) {
      url.host() == domain
    } else {
      domainMatch(url.host(), domain)
    }
    if (!domainMatch) return false

    if (!pathMatch(url, path)) return false

    return !secure || url.isHttps
  }

  /**
   * Builds a cookie. The [name], [value], and [domain] values must all be set before calling
   * [build].
   */
  class Builder {
    private var name: String? = null
    private var value: String? = null
    private var expiresAt = HttpDate.MAX_DATE
    private var domain: String? = null
    private var path = "/"
    private var secure: Boolean = false
    private var httpOnly: Boolean = false
    private var persistent: Boolean = false
    private var hostOnly: Boolean = false

    fun name(name: String) = apply {
      require(name.trim { it <= ' ' } == name) { "name is not trimmed" }
      this.name = name
    }

    fun value(value: String) = apply {
      require(value.trim { it <= ' ' } == value) { "value is not trimmed" }
      this.value = value
    }

    fun expiresAt(expiresAt: Long) = apply {
      var expiresAt = expiresAt
      if (expiresAt <= 0) expiresAt = Long.MIN_VALUE
      if (expiresAt > HttpDate.MAX_DATE) expiresAt = HttpDate.MAX_DATE
      this.expiresAt = expiresAt
      this.persistent = true
    }

    /**
     * Set the domain pattern for this cookie. The cookie will match `domain` and all of its
     * subdomains.
     */
    fun domain(domain: String): Builder = domain(domain, false)

    /**
     * Set the host-only domain for this cookie. The cookie will match `domain` but none of
     * its subdomains.
     */
    fun hostOnlyDomain(domain: String): Builder = domain(domain, true)

    private fun domain(domain: String, hostOnly: Boolean) = apply {
      val canonicalDomain = Util.canonicalizeHost(domain)
          ?: throw IllegalArgumentException("unexpected domain: $domain")
      this.domain = canonicalDomain
      this.hostOnly = hostOnly
    }

    fun path(path: String) = apply {
      require(path.startsWith("/")) { "path must start with '/'" }
      this.path = path
    }

    fun secure() = apply {
      this.secure = true
    }

    fun httpOnly() = apply {
      this.httpOnly = true
    }

    fun build(): Cookie {
      return Cookie(
          name ?: throw NullPointerException("builder.name == null"),
          value ?: throw NullPointerException("builder.value == null"),
          expiresAt,
          domain ?: throw NullPointerException("builder.domain == null"),
          path,
          secure,
          httpOnly,
          persistent,
          hostOnly)
    }
  }

  override fun toString(): String = toString(false)

  /**
   * @param forObsoleteRfc2965 true to include a leading `.` on the domain pattern. This is
   *     necessary for `example.com` to match `www.example.com` under RFC 2965. This extra dot is
   *     ignored by more recent specifications.
   */
  internal fun toString(forObsoleteRfc2965: Boolean): String {
    return buildString {
      append(name)
      append('=')
      append(value)

      if (persistent) {
        if (expiresAt == Long.MIN_VALUE) {
          append("; max-age=0")
        } else {
          append("; expires=").append(HttpDate.format(Date(expiresAt)))
        }
      }

      if (!hostOnly) {
        append("; domain=")
        if (forObsoleteRfc2965) {
          append(".")
        }
        append(domain)
      }

      append("; path=").append(path)

      if (secure) {
        append("; secure")
      }

      if (httpOnly) {
        append("; httponly")
      }

      return toString()
    }
  }

  companion object {
    private val YEAR_PATTERN = Pattern.compile("(\\d{2,4})[^\\d]*")
    private val MONTH_PATTERN =
        Pattern.compile("(?i)(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec).*")
    private val DAY_OF_MONTH_PATTERN = Pattern.compile("(\\d{1,2})[^\\d]*")
    private val TIME_PATTERN = Pattern.compile("(\\d{1,2}):(\\d{1,2}):(\\d{1,2})[^\\d]*")

    private fun domainMatch(urlHost: String, domain: String): Boolean {
      if (urlHost == domain) {
        return true // As in 'example.com' matching 'example.com'.
      }

      return urlHost.endsWith(domain)
          && urlHost[urlHost.length - domain.length - 1] == '.'
          && !verifyAsIpAddress(urlHost)
    }

    private fun pathMatch(url: HttpUrl, path: String): Boolean {
      val urlPath = url.encodedPath()

      if (urlPath == path) {
        return true // As in '/foo' matching '/foo'.
      }

      if (urlPath.startsWith(path)) {
        if (path.endsWith("/")) return true // As in '/' matching '/foo'.
        if (urlPath[path.length] == '/') return true // As in '/foo' matching '/foo/bar'.
      }

      return false
    }

    /**
     * Attempt to parse a `Set-Cookie` HTTP header value `setCookie` as a cookie. Returns null if
     * `setCookie` is not a well-formed cookie.
     */
    @JvmStatic
    fun parse(url: HttpUrl, setCookie: String): Cookie? =
        parse(System.currentTimeMillis(), url, setCookie)

    internal fun parse(currentTimeMillis: Long, url: HttpUrl, setCookie: String): Cookie? {
      var pos = 0
      val limit = setCookie.length
      val cookiePairEnd = delimiterOffset(setCookie, pos, limit, ';')

      val pairEqualsSign = delimiterOffset(setCookie, pos, cookiePairEnd, '=')
      if (pairEqualsSign == cookiePairEnd) return null

      val cookieName = trimSubstring(setCookie, pos, pairEqualsSign)
      if (cookieName.isEmpty() || indexOfControlOrNonAscii(cookieName) != -1) return null

      val cookieValue = trimSubstring(setCookie, pairEqualsSign + 1, cookiePairEnd)
      if (indexOfControlOrNonAscii(cookieValue) != -1) return null

      var expiresAt = HttpDate.MAX_DATE
      var deltaSeconds = -1L
      var domain: String? = null
      var path: String? = null
      var secureOnly = false
      var httpOnly = false
      var hostOnly = true
      var persistent = false

      pos = cookiePairEnd + 1
      while (pos < limit) {
        val attributePairEnd = delimiterOffset(setCookie, pos, limit, ';')

        val attributeEqualsSign = delimiterOffset(setCookie, pos, attributePairEnd, '=')
        val attributeName = trimSubstring(setCookie, pos, attributeEqualsSign)
        val attributeValue = if (attributeEqualsSign < attributePairEnd) {
          trimSubstring(setCookie, attributeEqualsSign + 1, attributePairEnd)
        } else {
          ""
        }

        when {
          attributeName.equals("expires", ignoreCase = true) -> {
            try {
              expiresAt = parseExpires(attributeValue, 0, attributeValue.length)
              persistent = true
            } catch (e: IllegalArgumentException) {
              // Ignore this attribute, it isn't recognizable as a date.
            }
          }
          attributeName.equals("max-age", ignoreCase = true) -> {
            try {
              deltaSeconds = parseMaxAge(attributeValue)
              persistent = true
            } catch (e: NumberFormatException) {
              // Ignore this attribute, it isn't recognizable as a max age.
            }
          }
          attributeName.equals("domain", ignoreCase = true) -> {
            try {
              domain = parseDomain(attributeValue)
              hostOnly = false
            } catch (e: IllegalArgumentException) {
              // Ignore this attribute, it isn't recognizable as a domain.
            }
          }
          attributeName.equals("path", ignoreCase = true) -> {
            path = attributeValue
          }
          attributeName.equals("secure", ignoreCase = true) -> {
            secureOnly = true
          }
          attributeName.equals("httponly", ignoreCase = true) -> {
            httpOnly = true
          }
        }

        pos = attributePairEnd + 1
      }

      // If 'Max-Age' is present, it takes precedence over 'Expires', regardless of the order the two
      // attributes are declared in the cookie string.
      if (deltaSeconds == Long.MIN_VALUE) {
        expiresAt = Long.MIN_VALUE
      } else if (deltaSeconds != -1L) {
        val deltaMilliseconds = if (deltaSeconds <= Long.MAX_VALUE / 1000) {
          deltaSeconds * 1000
        } else {
          Long.MAX_VALUE
        }
        expiresAt = currentTimeMillis + deltaMilliseconds
        if (expiresAt < currentTimeMillis || expiresAt > HttpDate.MAX_DATE) {
          expiresAt = HttpDate.MAX_DATE // Handle overflow & limit the date range.
        }
      }

      // If the domain is present, it must domain match. Otherwise we have a host-only cookie.
      val urlHost = url.host()
      if (domain == null) {
        domain = urlHost
      } else if (!domainMatch(urlHost, domain)) {
        return null // No domain match? This is either incompetence or malice!
      }

      // If the domain is a suffix of the url host, it must not be a public suffix.
      if (urlHost.length != domain.length
          && PublicSuffixDatabase.get().getEffectiveTldPlusOne(domain) == null) {
        return null
      }

      // If the path is absent or didn't start with '/', use the default path. It's a string like
      // '/foo/bar' for a URL like 'http://example.com/foo/bar/baz'. It always starts with '/'.
      if (path == null || !path.startsWith("/")) {
        val encodedPath = url.encodedPath()
        val lastSlash = encodedPath.lastIndexOf('/')
        path = if (lastSlash != 0) encodedPath.substring(0, lastSlash) else "/"
      }

      return Cookie(cookieName, cookieValue, expiresAt, domain, path, secureOnly, httpOnly,
          persistent, hostOnly)
    }

    /** Parse a date as specified in RFC 6265, section 5.1.1.  */
    private fun parseExpires(s: String, pos: Int, limit: Int): Long {
      var pos = pos
      pos = dateCharacterOffset(s, pos, limit, false)

      var hour = -1
      var minute = -1
      var second = -1
      var dayOfMonth = -1
      var month = -1
      var year = -1
      val matcher = TIME_PATTERN.matcher(s)

      while (pos < limit) {
        val end = dateCharacterOffset(s, pos + 1, limit, true)
        matcher.region(pos, end)

        when {
          hour == -1 && matcher.usePattern(TIME_PATTERN).matches() -> {
            hour = Integer.parseInt(matcher.group(1))
            minute = Integer.parseInt(matcher.group(2))
            second = Integer.parseInt(matcher.group(3))
          }
          dayOfMonth == -1 && matcher.usePattern(DAY_OF_MONTH_PATTERN).matches() -> {
            dayOfMonth = Integer.parseInt(matcher.group(1))
          }
          month == -1 && matcher.usePattern(MONTH_PATTERN).matches() -> {
            val monthString = matcher.group(1).toLowerCase(Locale.US)
            month = MONTH_PATTERN.pattern().indexOf(monthString) / 4 // Sneaky! jan=1, dec=12.
          }
          year == -1 && matcher.usePattern(YEAR_PATTERN).matches() -> {
            year = Integer.parseInt(matcher.group(1))
          }
        }

        pos = dateCharacterOffset(s, end + 1, limit, false)
      }

      // Convert two-digit years into four-digit years. 99 becomes 1999, 15 becomes 2015.
      if (year in 70..99) year += 1900
      if (year in 0..69) year += 2000

      // If any partial is omitted or out of range, return -1. The date is impossible. Note that leap
      // seconds are not supported by this syntax.
      require(year >= 1601)
      require(month != -1)
      require(dayOfMonth in 1..31)
      require(hour in 0..23)
      require(minute in 0..59)
      require(second in 0..59)

      GregorianCalendar(UTC).apply {
        isLenient = false
        set(Calendar.YEAR, year)
        set(Calendar.MONTH, month - 1)
        set(Calendar.DAY_OF_MONTH, dayOfMonth)
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, second)
        set(Calendar.MILLISECOND, 0)
        return timeInMillis
      }
    }

    /**
     * Returns the index of the next date character in `input`, or if `invert` the index
     * of the next non-date character in `input`.
     */
    private fun dateCharacterOffset(input: String, pos: Int, limit: Int, invert: Boolean): Int {
      for (i in pos until limit) {
        val c = input[i].toInt()
        val dateCharacter = (c < ' '.toInt() && c != '\t'.toInt() || c >= '\u007f'.toInt()
            || c in '0'.toInt()..'9'.toInt()
            || c in 'a'.toInt()..'z'.toInt()
            || c in 'A'.toInt()..'Z'.toInt()
            || c == ':'.toInt())
        if (dateCharacter == !invert) return i
      }
      return limit
    }

    /**
     * Returns the positive value if `attributeValue` is positive, or [Long.MIN_VALUE] if it is
     * either 0 or negative. If the value is positive but out of range, this returns
     * [Long.MAX_VALUE].
     *
     * @throws NumberFormatException if `s` is not an integer of any precision.
     */
    private fun parseMaxAge(s: String): Long {
      try {
        val parsed = s.toLong()
        return if (parsed <= 0L) Long.MIN_VALUE else parsed
      } catch (e: NumberFormatException) {
        // Check if the value is an integer (positive or negative) that's too big for a long.
        if (s.matches("-?\\d+".toRegex())) {
          return if (s.startsWith("-")) Long.MIN_VALUE else Long.MAX_VALUE
        }
        throw e
      }
    }

    /**
     * Returns a domain string like `example.com` for an input domain like `EXAMPLE.COM`
     * or `.example.com`.
     */
    private fun parseDomain(s: String): String {
      require(!s.endsWith("."))
      return canonicalizeHost(s.removePrefix(".")) ?: throw IllegalArgumentException()
    }

    /** Returns all of the cookies from a set of HTTP response headers.  */
    @JvmStatic
    fun parseAll(url: HttpUrl, headers: Headers): List<Cookie> {
      val cookieStrings = headers.values("Set-Cookie")
      var cookies: MutableList<Cookie>? = null

      for (i in 0 until cookieStrings.size) {
        val cookie = Cookie.parse(url, cookieStrings[i]) ?: continue
        if (cookies == null) cookies = mutableListOf()
        cookies.add(cookie)
      }

      return if (cookies != null) {
        Collections.unmodifiableList(cookies)
      } else {
        emptyList()
      }
    }
  }
}
