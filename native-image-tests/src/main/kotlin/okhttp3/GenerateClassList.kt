package okhttp3

import org.junit.jupiter.engine.descriptor.ClassBasedTestDescriptor
import org.junit.platform.engine.discovery.DiscoverySelectors
import java.io.File

/**
 * Run periodically to refresh the known set of working tests.
 *
 * TODO use filtering to allow skipping acceptable problem tests
 */
fun main() {
  val knownTestFile = File("native-image-tests/src/main/resources/testlist.txt")
  val testSelector = DiscoverySelectors.selectPackage("okhttp3")
  val testClasses = findTests(listOf(testSelector))
    .filter { it.isContainer }.mapNotNull { (it as? ClassBasedTestDescriptor)?.testClass }
  knownTestFile.writeText(testClasses.joinToString("\n") { it.name })
}