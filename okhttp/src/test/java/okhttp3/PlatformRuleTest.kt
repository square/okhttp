package okhttp3

import okhttp3.internal.platform.Platform
import org.junit.Rule
import org.junit.Test

/**
 * Sanity test for checking which environment and IDE is picking up.
 */
class PlatformRuleTest {
  @Suppress("RedundantVisibilityModifier")
  @JvmField
  @Rule public val platform = PlatformRule()

  @Test
  fun testMode() {
    println(PlatformRule.getPlatformSystemProperty())
    println(Platform.get().javaClass.simpleName)
  }
}