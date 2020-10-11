package okhttp3.mockwebserver.internal.duplex

open class MockWebServerAsserts {
  open fun assertEquals(expected: String, actual: String) {
    check(expected == actual)
  }

  open fun assertTrue(actual: Boolean) {
    check(actual)
  }

  open fun fail() {
    error("failed")
  }

  companion object {
    fun assertEquals(expected: String, actual: String) {
      instance.assertEquals(expected, actual)
    }

    fun assertTrue(actual: Boolean) {
      instance.assertTrue(actual)
    }

    fun fail() {
      instance.fail()
    }

    var instance: MockWebServerAsserts = MockWebServerAsserts()
  }
}