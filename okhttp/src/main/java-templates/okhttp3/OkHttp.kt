/*
 * Copyright (C) 2014 Square, Inc.
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
package okhttp3

object OkHttp {
  /**
   * This is a string like "4.5.0-RC1", "4.5.0", or "4.6.0-SNAPSHOT" indicating the version of
   * OkHttp in the current runtime. Use this to include the OkHttp version in custom `User-Agent`
   * headers.
   *
   * Official OkHttp releases follow [semantic versioning][semver]. Versions with the `-SNAPSHOT`
   * qualifier are not unique and should only be used in development environments. If you create
   * custom builds of OkHttp please include a qualifier your version name, like "4.7.0-mycompany.3".
   * The version string is configured in the root project's `build.gradle`.
   *
   * Note that OkHttp's runtime version may be different from the version specified in your
   * project's build file due to the dependency resolution features of your build tool.
   *
   * [semver]: https://semver.org
   */
  const val VERSION = "$projectVersion"
}
