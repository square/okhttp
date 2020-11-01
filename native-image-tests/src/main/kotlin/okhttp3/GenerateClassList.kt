package okhttp3

import org.junit.jupiter.engine.descriptor.ClassBasedTestDescriptor
import java.io.File

fun main() {
  val knownTestFile = File("native-image-tests/src/main/resources/testlist.txt")
  val testClasses = findTests().filter { it.isContainer }.mapNotNull { (it as? ClassBasedTestDescriptor)?.testClass }
  knownTestFile.writeText(testClasses.joinToString("\n") { it.name })
}