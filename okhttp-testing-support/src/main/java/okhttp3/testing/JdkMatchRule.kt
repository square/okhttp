package okhttp3.testing

import org.hamcrest.CoreMatchers
import org.hamcrest.Matcher
import org.hamcrest.TypeSafeMatcher
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
  override fun apply(base: Statement, description: Description): Statement {
    return object : Statement() {
      @Throws(Throwable::class)
      override fun evaluate() {
        try {
          base.evaluate()
          failIfExpected()
        } catch (e: Throwable) {
          rethrowIfNotExpected(e)
        }
      }
    }
  }

  fun expectFailure(versionMatcher: Matcher<VersionInfo>, failureMatcher: Matcher<*> = CoreMatchers.anything()) {
    // TODO implement
  }

  fun rethrowIfNotExpected(e: Throwable) {
    // TODO check expectations
    throw e
  }

  fun failIfExpected() {
    // TODO check expectations
  }
}