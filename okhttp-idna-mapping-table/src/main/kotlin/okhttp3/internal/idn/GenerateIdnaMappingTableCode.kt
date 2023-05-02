/*
 * Copyright (C) 2023 Square, Inc.
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
@file:JvmName("GenerateIdnaMappingTableCode")

package okhttp3.internal.idn

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.PropertySpec
import java.io.File
import okio.FileSystem
import okio.Path.Companion.toPath

fun main(vararg args: String) {
  val data = loadIdnaMappingTableData()
  val file = generateMappingTableFile(data)
  file.writeTo(File(args[0]))
}

fun loadIdnaMappingTableData(): IdnaMappingTableData {
  val path = "/okhttp3/internal/idna/IdnaMappingTable.txt".toPath()
  val table = FileSystem.RESOURCES.read(path) {
    readPlainTextIdnaMappingTable()
  }
  return buildIdnaMappingTableData(table)
}

/**
 * Generate a file containing the mapping table's string literals, like this:
 *
 * ```
 * internal val IDNA_MAPPING_TABLE: IdnaMappingTable = IdnaMappingTable(
 *   sections = "...",
 *   ranges = "...",
 *   mappings = "",
 * )
 * ```
 */
fun generateMappingTableFile(data: IdnaMappingTableData): FileSpec {
  val packageName = "okhttp3.internal.idn"
  val idnaMappingTable = ClassName(packageName, "IdnaMappingTable")

  return FileSpec.builder(packageName, "IdnaMappingTableInstance")
    .addProperty(
      PropertySpec.builder("IDNA_MAPPING_TABLE", idnaMappingTable)
        .addModifiers(KModifier.INTERNAL)
        .initializer(
          """
        |%T(⇥
        |sections = "%L",
        |ranges = "%L",
        |mappings = "%L",
        |⇤)
        """.trimMargin(),
          idnaMappingTable,
          data.sections.escapeDataString(),
          data.ranges.escapeDataString(),
          data.mappings.escapeDataString(),
        )
        .build()
    )
    .build()
}

/**
 * KotlinPoet doesn't really know what to do with a string containing NUL, BEL, DEL, etc. We also
 * don't want to perform `trimMargin()` at runtime.
 */
fun String.escapeDataString(): String {
  return buildString {
    for (codePoint in this@escapeDataString.codePoints()) {
      when (codePoint) {
        in 0..0x20,
        '"'.code,
        '$'.code,
        '\\'.code,
        '·'.code,
        127 -> append(String.format("\\u%04x", codePoint))

        else -> appendCodePoint(codePoint)
      }
    }
  }
}
