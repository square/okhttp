plugins {
  kotlin("jvm")
  application
  id("com.google.devtools.ksp")
}

application {
  mainClass.set("okhttp3.survey.RunSurveyKt")
}

dependencies {
  implementation(projects.okhttp)
  implementation(projects.okhttpCoroutines)
  implementation(libs.conscrypt.openjdk)

  implementation(libs.retrofit)
  implementation(libs.converter.moshi)
  implementation(libs.squareup.moshi)
  implementation(libs.squareup.moshi.kotlin)

  ksp(libs.squareup.moshi.compiler)
}

tasks.compileJava {
  options.isWarnings = false
}
