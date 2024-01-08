/*
 * Copyright 2014 Square Inc.
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
package okhttp3

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.SocketAddress
import java.net.SocketException
import java.nio.channels.SocketChannel
import java.util.function.BiFunction
import javax.net.ssl.HandshakeCompletedListener
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket

/** An [SSLSocket] that delegates all calls.  */
abstract class DelegatingSSLSocket(protected val delegate: SSLSocket?) : SSLSocket() {
  @Throws(IOException::class)
  override fun shutdownInput() {
    delegate!!.shutdownInput()
  }

  @Throws(IOException::class)
  override fun shutdownOutput() {
    delegate!!.shutdownOutput()
  }

  override fun getSupportedCipherSuites(): Array<String> {
    return delegate!!.supportedCipherSuites
  }

  override fun getEnabledCipherSuites(): Array<String> {
    return delegate!!.enabledCipherSuites
  }

  override fun setEnabledCipherSuites(suites: Array<String>) {
    delegate!!.enabledCipherSuites = suites
  }

  override fun getSupportedProtocols(): Array<String> {
    return delegate!!.supportedProtocols
  }

  override fun getEnabledProtocols(): Array<String> {
    return delegate!!.enabledProtocols
  }

  override fun setEnabledProtocols(protocols: Array<String>) {
    delegate!!.enabledProtocols = protocols
  }

  override fun getSession(): SSLSession {
    return delegate!!.session
  }

  override fun addHandshakeCompletedListener(listener: HandshakeCompletedListener) {
    delegate!!.addHandshakeCompletedListener(listener)
  }

  override fun removeHandshakeCompletedListener(listener: HandshakeCompletedListener) {
    delegate!!.removeHandshakeCompletedListener(listener)
  }

  @Throws(IOException::class)
  override fun startHandshake() {
    delegate!!.startHandshake()
  }

  override fun setUseClientMode(mode: Boolean) {
    delegate!!.useClientMode = mode
  }

  override fun getUseClientMode(): Boolean {
    return delegate!!.useClientMode
  }

  override fun setNeedClientAuth(need: Boolean) {
    delegate!!.needClientAuth = need
  }

  override fun setWantClientAuth(want: Boolean) {
    delegate!!.wantClientAuth = want
  }

  override fun getNeedClientAuth(): Boolean {
    return delegate!!.needClientAuth
  }

  override fun getWantClientAuth(): Boolean {
    return delegate!!.wantClientAuth
  }

  override fun setEnableSessionCreation(flag: Boolean) {
    delegate!!.enableSessionCreation = flag
  }

  override fun getEnableSessionCreation(): Boolean {
    return delegate!!.enableSessionCreation
  }

  override fun getSSLParameters(): SSLParameters {
    return delegate!!.sslParameters
  }

  override fun setSSLParameters(p: SSLParameters) {
    delegate!!.sslParameters = p
  }

  @Throws(IOException::class)
  override fun close() {
    delegate!!.close()
  }

  override fun getInetAddress(): InetAddress {
    return delegate!!.inetAddress
  }

  @Throws(IOException::class)
  override fun getInputStream(): InputStream {
    return delegate!!.inputStream
  }

  @Throws(SocketException::class)
  override fun getKeepAlive(): Boolean {
    return delegate!!.keepAlive
  }

  override fun getLocalAddress(): InetAddress {
    return delegate!!.localAddress
  }

  override fun getLocalPort(): Int {
    return delegate!!.localPort
  }

  @Throws(IOException::class)
  override fun getOutputStream(): OutputStream {
    return delegate!!.outputStream
  }

  override fun getPort(): Int {
    return delegate!!.port
  }

  @Throws(SocketException::class)
  override fun getSoLinger(): Int {
    return delegate!!.soLinger
  }

  @Throws(SocketException::class)
  override fun getReceiveBufferSize(): Int {
    return delegate!!.receiveBufferSize
  }

  @Throws(SocketException::class)
  override fun getSendBufferSize(): Int {
    return delegate!!.sendBufferSize
  }

  @Throws(SocketException::class)
  override fun getSoTimeout(): Int {
    return delegate!!.soTimeout
  }

  @Throws(SocketException::class)
  override fun getTcpNoDelay(): Boolean {
    return delegate!!.tcpNoDelay
  }

  @Throws(SocketException::class)
  override fun setKeepAlive(keepAlive: Boolean) {
    delegate!!.keepAlive = keepAlive
  }

  @Throws(SocketException::class)
  override fun setSendBufferSize(size: Int) {
    delegate!!.sendBufferSize = size
  }

  @Throws(SocketException::class)
  override fun setReceiveBufferSize(size: Int) {
    delegate!!.receiveBufferSize = size
  }

  @Throws(SocketException::class)
  override fun setSoLinger(
    on: Boolean,
    timeout: Int,
  ) {
    delegate!!.setSoLinger(on, timeout)
  }

  @Throws(SocketException::class)
  override fun setSoTimeout(timeout: Int) {
    delegate!!.soTimeout = timeout
  }

  @Throws(SocketException::class)
  override fun setTcpNoDelay(on: Boolean) {
    delegate!!.tcpNoDelay = on
  }

  override fun toString(): String {
    return delegate!!.toString()
  }

  override fun getLocalSocketAddress(): SocketAddress {
    return delegate!!.localSocketAddress
  }

  override fun getRemoteSocketAddress(): SocketAddress {
    return delegate!!.remoteSocketAddress
  }

  override fun isBound(): Boolean {
    return delegate!!.isBound
  }

  override fun isConnected(): Boolean {
    return delegate!!.isConnected
  }

  override fun isClosed(): Boolean {
    return delegate!!.isClosed
  }

  @Throws(IOException::class)
  override fun bind(localAddr: SocketAddress) {
    delegate!!.bind(localAddr)
  }

  @Throws(IOException::class)
  override fun connect(remoteAddr: SocketAddress) {
    delegate!!.connect(remoteAddr)
  }

  @Throws(IOException::class)
  override fun connect(
    remoteAddr: SocketAddress,
    timeout: Int,
  ) {
    delegate!!.connect(remoteAddr, timeout)
  }

  override fun isInputShutdown(): Boolean {
    return delegate!!.isInputShutdown
  }

  override fun isOutputShutdown(): Boolean {
    return delegate!!.isOutputShutdown
  }

  @Throws(SocketException::class)
  override fun setReuseAddress(reuse: Boolean) {
    delegate!!.reuseAddress = reuse
  }

  @Throws(SocketException::class)
  override fun getReuseAddress(): Boolean {
    return delegate!!.reuseAddress
  }

  @Throws(SocketException::class)
  override fun setOOBInline(oobinline: Boolean) {
    delegate!!.oobInline = oobinline
  }

  @Throws(SocketException::class)
  override fun getOOBInline(): Boolean {
    return delegate!!.oobInline
  }

  @Throws(SocketException::class)
  override fun setTrafficClass(value: Int) {
    delegate!!.trafficClass = value
  }

  @Throws(SocketException::class)
  override fun getTrafficClass(): Int {
    return delegate!!.trafficClass
  }

  @Throws(IOException::class)
  override fun sendUrgentData(value: Int) {
    delegate!!.sendUrgentData(value)
  }

  override fun getChannel(): SocketChannel {
    return delegate!!.channel
  }

  override fun getHandshakeSession(): SSLSession {
    return delegate!!.handshakeSession
  }

  override fun getApplicationProtocol(): String {
    return delegate!!.applicationProtocol
  }

  override fun getHandshakeApplicationProtocol(): String {
    return delegate!!.handshakeApplicationProtocol
  }

  override fun setHandshakeApplicationProtocolSelector(selector: BiFunction<SSLSocket, MutableList<String>, String>?) {
    delegate!!.handshakeApplicationProtocolSelector = selector
  }

  override fun getHandshakeApplicationProtocolSelector(): BiFunction<SSLSocket, MutableList<String>, String> {
    return delegate!!.handshakeApplicationProtocolSelector
  }
}
