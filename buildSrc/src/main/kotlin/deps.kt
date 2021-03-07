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

object Versions {
  const val animalSniffer = "1.19"
  const val assertj = "3.15.0"
  const val bnd = "5.1.2"
  const val bouncycastle = "1.67"
  const val brotli = "0.1.2"
  const val checkstyle = "8.28"
  const val conscrypt = "2.5.1"
  const val corretto = "1.3.1"
  const val equinox = "3.16.0"
  const val findbugs = "3.0.2"
  const val guava = "28.2-jre"
  const val jnrUnixsocket = "0.28"
  const val jsoup = "1.13.1"
  const val junit = "4.13"
  const val junit5 = "5.7.0"
  const val kotlin = "1.4.21"
  const val ktlint = "0.38.0"
  const val moshi = "1.11.0"
  const val okio = "3.0.0-alpha.1"
  const val openjsse = "1.1.0"
  const val picocli = "4.5.1"
}

object Dependencies {
  const val android = "org.robolectric:android-all:11-robolectric-6757853"
  const val animalSniffer = "org.codehaus.mojo:animal-sniffer-annotations:${Versions.animalSniffer}"
  const val assertj = "org.assertj:assertj-core:${Versions.assertj}"
  const val bnd = "biz.aQute.bnd:biz.aQute.bnd.gradle:${Versions.bnd}"
  const val bndResolve = "biz.aQute.bnd:biz.aQute.resolve:${Versions.bnd}"
  const val bouncycastle = "org.bouncycastle:bcprov-jdk15to18:${Versions.bouncycastle}"
  const val bouncycastlepkix = "org.bouncycastle:bcpkix-jdk15to18:${Versions.bouncycastle}"
  const val bouncycastletls = "org.bouncycastle:bctls-jdk15to18:${Versions.bouncycastle}"
  const val brotli = "org.brotli:dec:${Versions.brotli}"
  const val conscrypt = "org.conscrypt:conscrypt-openjdk-uber:${Versions.conscrypt}"
  const val corretto = "software.amazon.cryptools:AmazonCorrettoCryptoProvider:${Versions.corretto}:linux-x86_64"
  const val equinox = "org.eclipse.platform:org.eclipse.osgi:${Versions.equinox}"
  const val guava = "com.google.guava:guava:${Versions.guava}"
  const val hamcrest = "org.hamcrest:hamcrest-library:2.1"
  const val jnrUnixsocket = "com.github.jnr:jnr-unixsocket:${Versions.jnrUnixsocket}"
  const val jsoup = "org.jsoup:jsoup:${Versions.jsoup}"
  const val jsr305 = "com.google.code.findbugs:jsr305:${Versions.findbugs}"
  const val junit = "junit:junit:${Versions.junit}"
  const val junit5Api = "org.junit.jupiter:junit-jupiter-api:${Versions.junit5}"
  const val junit5JupiterEngine = "org.junit.jupiter:junit-jupiter-engine:${Versions.junit5}"
  const val junit5JupiterParams = "org.junit.jupiter:junit-jupiter-params:${Versions.junit5}"
  const val junit5VintageEngine = "org.junit.vintage:junit-vintage-engine:${Versions.junit5}"
  const val junitPlatformConsole = "org.junit.platform:junit-platform-console:1.7.0"
  const val kotlinStdlib = "org.jetbrains.kotlin:kotlin-stdlib:${Versions.kotlin}"
  const val kotlinStdlibOsgi = "org.jetbrains.kotlin:kotlin-osgi-bundle:${Versions.kotlin}"
  const val moshi = "com.squareup.moshi:moshi:${Versions.moshi}"
  const val moshiKotlin = "com.squareup.moshi:moshi-kotlin-codegen:${Versions.moshi}"
  const val okio = "com.squareup.okio:okio:${Versions.okio}"
  const val okioFakeFileSystem = "com.squareup.okio:okio-fakefilesystem:${Versions.okio}"
  const val openjsse = "org.openjsse:openjsse:${Versions.openjsse}"
  const val picocli = "info.picocli:picocli:${Versions.picocli}"

  object Kotlin {
    const val version = "1.4.30"
  }
}