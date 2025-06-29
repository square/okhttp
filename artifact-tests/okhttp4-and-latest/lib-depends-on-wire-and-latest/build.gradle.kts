plugins {
  alias(libs.plugins.kotlin.jvm)
}

dependencies {
  implementation("com.squareup.wire:wire-grpc-client:4.9.9")
  implementation(project(":lib-depends-on-latest"))
  testImplementation(libs.assertk)
  testImplementation(project(":classpathscanner"))
}
