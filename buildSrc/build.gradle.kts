/*
 * Copyright (C) 2021 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
}

dependencies {
  // TODO (https://github.com/square/okhttp/issues/8612) we will need a consistent version
  // 7.1.0 is used because it avoids this error
  // Could not create an instance of type aQute.bnd.gradle.BundleTaskExtension.
  // Cannot change attributes of configuration ':native-image-tests:compileClasspath' after it has been locked for mutation
  implementation("biz.aQute.bnd:biz.aQute.bnd.gradle:7.1.0")
}
