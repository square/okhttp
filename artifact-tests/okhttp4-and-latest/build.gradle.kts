subprojects {
  @Suppress("UnstableApiUsage")
  plugins.withType<TestSuiteBasePlugin> {
    extensions.configure<TestingExtension> {
      suites.withType<JvmTestSuite>().configureEach {
        useKotlinTest()
      }
    }
  }
}
