package artifacttests

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.exactly
import kotlin.test.Test

class DependsOn4xAndLatestJvmTest {
  @Test
  fun exactlyOneOkHttpJvmDependency() {
    assertThat(ClasspathScanner.roots)
      .exactly(1) { assert ->
        assert
          .transform { path -> path.toString() }
          .contains("/com.squareup.okhttp3/okhttp-jvm/")
      }
  }

  @Test
  fun noOkHttp4Dependency() {
    assertThat(ClasspathScanner.roots)
      .exactly(0) { assert ->
        assert
          .transform { path -> path.toString() }
          .contains("/com.squareup.okhttp3/okhttp/")
      }
  }
}
