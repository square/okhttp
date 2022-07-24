plugins {
  kotlin("jvm")
  application
}

application {
  mainClass.set("okhttp3.survey.RunSurveyKt")
}

dependencies {
  implementation(projects.okhttp)
  implementation(projects.okhttpCoroutines)
  implementation(libs.conscrypt.openjdk)
}

tasks.compileJava {
  options.isWarnings = false
}
