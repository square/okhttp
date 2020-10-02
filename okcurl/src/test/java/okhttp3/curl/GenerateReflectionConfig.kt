/*
 * Copyright (C) 2020 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okhttp3.curl

import picocli.CommandLine.Model.CommandSpec.forAnnotatedObject
import picocli.codegen.aot.graalvm.ReflectionConfigGenerator
import java.io.File

/**
 * Manual process to update reflect-config.json
 */
fun main() {
  val configFile = File("okcurl/src/main/resources/META-INF/native-image/okhttp3/okcurl/reflect-config.json")
  configFile.writeText(ReflectionConfigGenerator.generateReflectionConfig(forAnnotatedObject(Main())))
}