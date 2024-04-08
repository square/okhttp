plugins {
  kotlin("jvm")
  application
  id("com.google.devtools.ksp").version("1.9.23-1.0.20")
}

application {
  mainClass.set("okhttp3.survey.RunSurveyKt")
}

dependencies {
  implementation(projects.okhttp)
  implementation(projects.okhttpCoroutines)
  implementation(libs.conscrypt.openjdk)

  implementation("com.squareup.retrofit2:retrofit:2.11.0")
  implementation("com.squareup.retrofit2:converter-moshi:2.11.0")
  implementation(libs.squareup.moshi)
  implementation(libs.squareup.moshi.kotlin)

  ksp("com.squareup.moshi:moshi-kotlin-codegen:1.15.1")
}

tasks.compileJava {
  options.isWarnings = false
}
