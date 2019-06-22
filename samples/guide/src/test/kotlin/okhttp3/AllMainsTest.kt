package okhttp3

import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.lang.reflect.InvocationTargetException

@RunWith(Parameterized::class)
class AllMainsTest(val className: String) {
  @Test
  fun runMain() {
    val mainMethod = Class.forName(className).methods.find { it.name == "main" }
    try {
      mainMethod?.invoke(null, arrayOf<String>())
    } catch (ite: InvocationTargetException) {
      throw ite.targetException
    }
  }

  companion object {
    @JvmStatic
    @Parameterized.Parameters(name = "{0}")
    fun data(): List<String> {
      val mainFiles = mainFiles()
      return mainFiles.map {
        it.path.substring("$prefix/samples/guide/src/main/java".length, it.path.length - 5).replace('/', '.')
      }.sorted()
    }

    private fun mainFiles(): List<File> {
      return File("$prefix/samples/guide/src/main/java/okhttp3").listFiles()?.flatMap {
        it?.listFiles()?.toList().orEmpty()
      }.orEmpty()
    }
  }
}