package okhttp3.testing

import org.junit.Rule
import org.junit.Test

class JdkMatchRuleTest {
  @Suppress("RedundantVisibilityModifier")
  @JvmField
  @Rule public var jdkMatchRule = JdkMatchRule()

  @Test
  fun testGreenCase() {
  }

  @Test
  fun testGreenCaseFailingOnLater() {
    jdkMatchRule.expectFailure(fromMajor(VersionInfo.majorVersion + 1))
  }

  @Test
  fun failureCase() {
    jdkMatchRule.expectFailure(fromMajor(VersionInfo.majorVersion))

    check(false)
  }
}