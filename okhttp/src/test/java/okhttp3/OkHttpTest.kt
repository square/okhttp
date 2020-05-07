package okhttp3

import org.junit.Test

class OkHttpTest {
    @Test
    fun testVersion() {
        val semVerRegex = Regex("^[0-9]+\\.[0-9]+\\.[0-9]+(-.+)?$")
        assert(semVerRegex.matches(VERSION))
    }
}
