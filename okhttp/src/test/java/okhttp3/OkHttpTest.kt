package okhttp3

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class OkHttpTest {
    @Test
    fun testVersion() {
        assertThat(VERSION).matches("[0-9]+\\.[0-9]+\\.[0-9]+(-.+)?")
    }
}
