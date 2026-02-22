/*
 * Copyright (c) 2026 Block, Inc.
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
@file:OptIn(ExperimentalTime::class)

package mockwebserver.socket

import io.pkts.PcapOutputStream
import io.pkts.buffer.Buffers
import io.pkts.frame.PcapGlobalHeader
import io.pkts.frame.PcapRecordHeader
import io.pkts.packet.impl.PCapPacketImpl
import java.io.Closeable
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import okio.Buffer
import okio.FileSystem
import okio.Path
import okio.buffer

public class PcapRecorder(
  file: Path,
  fileSystem: FileSystem = FileSystem.SYSTEM,
) : SocketEventListener,
  Closeable {
  private val globalHeader = PcapGlobalHeader.createDefaultHeader()
  private val out = PcapOutputStream.create(globalHeader, fileSystem.sink(file).buffer().outputStream())
  private var closed = false

  // Track synthetic sequence numbers per socket to map TCP window flow
  private val sequenceNumbers = mutableMapOf<String, Long>()
  private val ackNumbers = mutableMapOf<String, Long>()

  public val simulateTcp: Boolean = false

  override fun onEvent(event: SocketEvent) {
    synchronized(this) {
      if (closed) return

      var seq = sequenceNumbers.getOrDefault(event.socketName, 1000L)
      var ack = ackNumbers.getOrDefault(event.socketName, 1000L)

      when (event) {
        is SocketEvent.Connect -> {
          if (simulateTcp) {
            // SYN
            writePacket(
              out,
              event.timestamp,
              event.connection,
              seq,
              ack,
              syn = true,
              ackFlag = false,
              payload = null,
            )
            seq++
          }
        }

        is SocketEvent.WriteSuccess -> {
          // PSH, ACK
          val payloadBytes = event.payload?.readByteArray()
          writePacket(
            out,
            event.timestamp,
            event.connection,
            seq,
            ack,
            syn = false,
            ackFlag = true,
            psh = true,
            payload = payloadBytes,
          )
          if (payloadBytes != null) seq += payloadBytes.size
        }

        is SocketEvent.ReadSuccess -> {
          // For reads, we write from the perspective of the server sending to the client
          val payloadBytes = event.payload?.readByteArray()
          writePacket(
            out,
            event.timestamp,
            event.connection,
            ack,
            seq,
            syn = false,
            ackFlag = true,
            psh = true,
            payload = payloadBytes,
            clientSide = false,
          )
          if (payloadBytes != null) ack += payloadBytes.size
        }

        is SocketEvent.Close -> {
          // FIN, ACK
          writePacket(
            out,
            event.timestamp,
            event.connection,
            seq,
            ack,
            syn = false,
            ackFlag = true,
            fin = true,
            payload = null,
          )
          seq++
        }

        else -> {}
      }

      sequenceNumbers[event.socketName] = seq
      ackNumbers[event.socketName] = ack
    }
  }

  override fun close() {
    synchronized(this) {
      if (closed) return
      closed = true
      out.close()
    }
  }

  private fun writePacket(
    out: PcapOutputStream,
    timestamp: Instant,
    socketConnection: SocketEvent.SocketConnection,
    seq: Long,
    ack: Long,
    clientSide: Boolean = true,
    syn: Boolean = false,
    ackFlag: Boolean = false,
    fin: Boolean = false,
    psh: Boolean = false,
    payload: ByteArray? = null,
  ) {
    // Because pkts.io is built around reading packets rather than forging them from scratch natively as a builder
    // we manually construct a raw Ethernet + IPv4 + TCP packet byte string for the writer, using standard standard header lengths.

    val tcpLen = 20 + (payload?.size ?: 0)
    val ipv4Len = 20 + tcpLen
    val totalLen = 14 + ipv4Len

    val pkt = Buffer()

    // Ethernet (14 bytes)
    pkt.write(ByteArray(6) { 0x00 }) // Dst MAC
    pkt.write(ByteArray(6) { 0x00 }) // Src MAC
    pkt.writeShort(0x0800) // Type IPv4

    // IPv4 (20 bytes)
    pkt.writeByte(0x45) // Version 4, IHL 5
    pkt.writeByte(0x00) // DSCP
    pkt.writeShort(ipv4Len) // Total Length
    pkt.writeShort(0x0000) // Identification
    pkt.writeShort(0x4000) // Flags + Fragment offset
    pkt.writeByte(0x40) // TTL 64
    pkt.writeByte(0x06) // Protocol TCP (6)
    pkt.writeShort(0x0000) // Checksum (ignored by most readers if missing)

    if (clientSide) {
      pkt.write(socketConnection.local.address.address)
      pkt.write(socketConnection.peer.address.address)
      pkt.writeShort(socketConnection.local.port)
      pkt.writeShort(socketConnection.peer.port)
    } else {
      pkt.write(socketConnection.peer.address.address)
      pkt.write(socketConnection.local.address.address)
      pkt.writeShort(socketConnection.peer.port)
      pkt.writeShort(socketConnection.local.port)
    }

    // TCP (20 bytes)
    pkt.writeInt(seq.toInt()) // Sequence Number
    pkt.writeInt(ack.toInt()) // Ack Number

    val dataOffset = (5 shl 4).toByte()
    pkt.writeByte(dataOffset.toInt())

    var flags = 0
    if (fin) flags = flags or 0x01
    if (syn) flags = flags or 0x02
    if (psh) flags = flags or 0x08
    if (ackFlag) flags = flags or 0x10
    pkt.writeByte(flags)

    pkt.writeShort(65535) // Window size
    pkt.writeShort(0x0000) // Checksum
    pkt.writeShort(0x0000) // Urgent pointer

    // Payload
    if (payload != null) {
      pkt.write(payload)
    }

    val rawPkt = pkt.readByteArray()
    val recordHeader = PcapRecordHeader.createDefaultHeader(timestamp.toEpochMilliseconds())
    recordHeader.capturedLength = rawPkt.size.toLong()
    recordHeader.totalLength = rawPkt.size.toLong()
    val frame =
      PCapPacketImpl(
        globalHeader,
        recordHeader,
        Buffers.wrap(rawPkt),
      )
    out.write(frame)
  }
}
