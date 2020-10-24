package okhttp3

import org.junit.platform.console.ConsoleLauncher

fun main() {
  System.setProperty("junit.jupiter.extensions.autodetection.enabled", "true")
  ConsoleLauncher.main("-p", "okhttp3")
}
