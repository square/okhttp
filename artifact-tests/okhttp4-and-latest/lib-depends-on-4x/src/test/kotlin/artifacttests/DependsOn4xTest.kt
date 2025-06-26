package artifacttests

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.exactly
import kotlin.test.Test

class DependsOn4xTest {
  @Test
  fun noOkHttpJvmDependency() {
    assertThat(ClasspathScanner.roots)
      .exactly(0) { assert ->
        assert
          .transform { path -> path.toString() }
          .contains("/com.squareup.okhttp3/okhttp-jvm/")
      }
  }

  @Test
  fun exactlyOneOkHttp4Dependency() {
    assertThat(ClasspathScanner.roots)
      .exactly(1) { assert ->
        assert
          .transform { path -> path.toString() }
          .contains("/com.squareup.okhttp3/okhttp/")
      }
  }
}
