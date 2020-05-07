package okhttp3

import org.junit.Test
import org.assertj.core.api.Assertions.assertThat

class OkHttpTest {
    @Test
    fun testVersion() {
        assertThat(VERSION).matches("[0-9]+\\.[0-9]+\\.[0-9]+(-.+)?")
    }
}
