/*
 * Copyright (C) 2019 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okhttp3.stress

import okhttp3.Connection
import okhttp3.internal.connection.RealConnection
import java.util.ArrayDeque
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal class StatsCollector {
  private val latestStats = AtomicReference<Stats>()
  private val connections = Collections.newSetFromMap(
      ConcurrentHashMap<RealConnection, Boolean>())

  fun addConnection(connection: Connection?) {
    connections += connection as RealConnection
  }

  fun addStats(stats: Stats) {
    while (true) {
      val before = latestStats.get()
      val combined = before?.plus(stats) ?: stats

      val connectionStats = connectionStats()
      val nanotime = System.nanoTime()
      val after = combined.copy(
          nanotime = nanotime,
          totalBytesRead = connectionStats.totalBytesRead,
          acknowledgedBytesRead = connectionStats.acknowledgedBytesRead,
          writeBytesTotal = connectionStats.writeBytesTotal,
          writeBytesMaximum = connectionStats.writeBytesMaximum
      )

      if (latestStats.compareAndSet(before, after)) return
    }
  }

  private fun connectionStats(): Stats {
    var totalBytesRead = 0L
    var acknowledgedBytesRead = 0L
    var writeBytesTotal = 0L
    var writeBytesMaximum = 0L

    for (connection in connections) {
      totalBytesRead += connection.readBytesTotal
      acknowledgedBytesRead += connection.readBytesAcknowledged
      writeBytesTotal += connection.writeBytesTotal
      writeBytesMaximum += connection.writeBytesMaximum
    }

    return Stats(
        totalBytesRead = totalBytesRead,
        acknowledgedBytesRead = acknowledgedBytesRead,
        writeBytesTotal = writeBytesTotal,
        writeBytesMaximum = writeBytesMaximum
    )
  }

  fun printStatsContinuously(logsPerSecond: Int) {
    val rollingWindow = ArrayDeque<Stats>()
    rollingWindow += latestStats.get()

    while (true) {
      val previous = rollingWindow.first
      val stats = latestStats.get()
      printStatsLine(stats, previous)
      rollingWindow.addLast(stats)

      // Trim the window to 10 seconds
      while (TimeUnit.NANOSECONDS.toSeconds(stats.nanotime - rollingWindow.first.nanotime) > 10) {
        rollingWindow.removeFirst()
      }

      Thread.sleep((1000.0 / logsPerSecond).toLong())
    }
  }

  private fun printStatsLine(current: Stats, previous: Stats) {
    val elapsedSeconds = (current.nanotime - previous.nanotime) / 1e9
    val qps = (current.clientResponses - previous.clientResponses) / elapsedSeconds
    println("${"%.2f".format(qps)} qps $current")
  }
}
