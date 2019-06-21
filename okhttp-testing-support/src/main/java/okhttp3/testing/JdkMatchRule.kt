package okhttp3.testing

import org.hamcrest.CoreMatchers
import org.hamcrest.Matcher
import org.hamcrest.StringDescription
import org.hamcrest.TypeSafeMatcher
import org.junit.Assert.fail
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

fun fromMajor(version: Int): Matcher<VersionInfo> {
  return object : TypeSafeMatcher<VersionInfo>() {
    override fun describeTo(description: org.hamcrest.Description) {
      description.appendText("JDK with version from $version")
    }

    override fun matchesSafely(item: VersionInfo): Boolean {
      return item.majorVersion >= version
    }
  }
}

object VersionInfo {
  val majorVersion: Int by lazy {
    val jvmSpecVersion = getJvmSpecVersion()

    when (jvmSpecVersion) {
      "1.8" -> 8
      else -> jvmSpecVersion.toInt()
    }
  }

  fun getJvmSpecVersion(): String {
    return System.getProperty("java.specification.version", "unknown")
  }
}

class JdkMatchRule : TestRule {
  val versionChecks = mutableListOf<Pair<Matcher<VersionInfo>, Matcher<out Any>>>()

  override fun apply(base: Statement, description: Description): Statement {
    return object : Statement() {
      @Throws(Throwable::class)
      override fun evaluate() {
        var failed = false
        try {
          base.evaluate()
        } catch (e: Throwable) {
          failed = true
          rethrowIfNotExpected(e)
        }
        if (!failed) {
          failIfExpected()
        }
      }
    }
  }

  fun expectFailure(versionMatcher: Matcher<VersionInfo>, failureMatcher: Matcher<out Any> = CoreMatchers.anything()) {
    versionChecks.add(Pair(versionMatcher, failureMatcher))
  }

  fun rethrowIfNotExpected(e: Throwable) {
    versionChecks.forEach { (versionMatcher, failureMatcher) ->
      if (versionMatcher.matches(VersionInfo)) {
        if (!failureMatcher.matches(failureMatcher.matches(e))) {
          // TODO should we log mismatch decsription?
          throw e
        }

        return
      }
    }

    throw e
  }

  fun failIfExpected() {
    versionChecks.forEach { (versionMatcher, failureMatcher) ->
      if (versionMatcher.matches(VersionInfo)) {
        val description = StringDescription()
        versionMatcher.describeTo(description)
        description.appendText(" expected to fail with exception that ")
        failureMatcher.describeTo(description)

        fail(description.toString())
      }
    }
  }
}