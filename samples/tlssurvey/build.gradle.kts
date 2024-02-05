plugins {
  kotlin("jvm")
  application
  id("com.google.devtools.ksp").version("1.9.22-1.0.17")
}

application {
  mainClass.set("okhttp3.survey.RunSurveyKt")
}

dependencies {
  implementation("com.squareup.okhttp3:okhttp:5.0.0-alpha.10")
  implementation("com.squareup.okhttp3:okhttp-coroutines:5.0.0-alpha.12")
  implementation(libs.conscrypt.openjdk)

  implementation("com.squareup.retrofit2:retrofit:2.9.0")
  implementation("com.squareup.retrofit2:converter-moshi:2.9.0")
  implementation(libs.squareup.moshi)
  implementation(libs.squareup.moshi.kotlin)

  ksp("com.squareup.moshi:moshi-kotlin-codegen:1.15.1")
}

tasks.compileJava {
  options.isWarnings = false
}
