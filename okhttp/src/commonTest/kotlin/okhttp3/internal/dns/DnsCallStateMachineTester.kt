@file:OptIn(OkHttpInternalApi::class, ExperimentalTime::class)

package okhttp3.internal.dns

import assertk.assertThat
import assertk.assertions.hasMessage
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.LinkedBlockingDeque
import kotlin.time.ExperimentalTime
import okhttp3.Dns
import okhttp3.Protocol
import okhttp3.dnsResponse
import okhttp3.internal.OkHttpInternalApi
import okhttp3.internal.concurrent.TaskFaker
import okhttp3.internal.dns.DnsCallStateMachine.Transport
import okhttp3.internal.dns.DnsCallStateMachineTester.CallEvent.OnFailure
import okhttp3.internal.dns.DnsCallStateMachineTester.CallEvent.OnRecords
import okhttp3.internal.dns.DnsCallStateMachineTester.TransportEvent.QueryCanceled
import okhttp3.internal.dns.DnsCallStateMachineTester.TransportEvent.QueryEnqueued
import okhttp3.internal.dns.DnsMessage.Companion.query
import okio.ByteString

/**
 * Test the DNS state machine.
 *
 * This has helpers to operate on the state machine:
 *
 * This tracks all effects from the state machine as events: creating queries, canceling queries,
 * calling callbacks.
 */
fun testDnsCallStateMachine(block: DnsCallStateMachineTester.() -> Unit) {
  val tester = DnsCallStateMachineTester()
  tester.block()
  assertThat(tester.transport.events.poll(), "unexpected transport event").isNull()
}

class DnsCallStateMachineTester internal constructor() {
  var onNextEvent: (() -> Unit)? = null

  /** Defend against re-entrant calls. */
  private var acceptCallbacks: Boolean = true

  val transport = Transport()

  private val taskFaker = TaskFaker()

  private val cachingTransport =
    CachingTransport<Query>(
      taskRunner = taskFaker.taskRunner,
      delegate = transport,
      timeSource = taskFaker.timeSource,
    )

  fun newCall(
    request: Dns.Request,
    includeIPv6: Boolean = true,
    includeServiceMetadata: Boolean = true,
    caching: Boolean = false,
  ): Call =
    Call(
      request = request,
      includeIPv6 = includeIPv6,
      includeServiceMetadata = includeServiceMetadata,
      caching = caching,
    )

  inner class Transport : DnsCallStateMachine.Transport<Query> {
    val events = LinkedBlockingDeque<TransportEvent>()

    override fun newQuery(question: Question) = Query(question)

    private fun postEvent(e: TransportEvent) {
      events.put(e)

      onNextEvent?.invoke()
      onNextEvent = null
    }

    private fun takeEvent(): TransportEvent {
      taskFaker.runTasks() // Run any queued async work first.
      return events.take()
    }

    /** Asserts that the next-posted event is a query enqueue. */
    fun takeQuery(
      hostname: String,
      type: Int,
    ): QueryEnqueued {
      val event = transport.takeEvent() as QueryEnqueued
      assertThat(event.hostname).isEqualTo(hostname)
      assertThat(event.type).isEqualTo(type)
      return event
    }

    /** Asserts that the next-posted event is a query cancel. */
    fun takeCancel(
      hostname: String,
      type: Int,
    ): QueryCanceled {
      val event = transport.takeEvent() as QueryCanceled
      assertThat(event.hostname).isEqualTo(hostname)
      assertThat(event.type).isEqualTo(type)
      return event
    }

    override fun enqueue(
      query: Query,
      callback: Transport.Callback<Query>,
    ) {
      postEvent(QueryEnqueued(query, callback))
    }

    override fun cancel(query: Query) {
      postEvent(QueryCanceled(query))
    }
  }

  /** A DNS call for the fake state machine. */
  inner class Call(
    override val request: Dns.Request,
    includeIPv6: Boolean = true,
    includeServiceMetadata: Boolean = true,
    caching: Boolean = false,
  ) : Dns.Call,
    Dns.Callback {
    private val events = LinkedBlockingDeque<CallEvent>()

    val stateMachine =
      DnsCallStateMachine(
        transport =
          when {
            caching -> cachingTransport
            else -> transport
          },
        call = this,
        canceledException = null,
        includeIPv6 = includeIPv6,
        includeServiceMetadata = includeServiceMetadata,
      )

    fun enqueue() {
      check(acceptCallbacks) { "unexpected enqueue" }
      acceptCallbacks = false
      try {
        enqueue(this)
      } finally {
        acceptCallbacks = true
      }
    }

    override fun enqueue(callback: Dns.Callback) {
      stateMachine.start(callback)
    }

    override fun cancel() {
      stateMachine.cancel()
    }

    override fun isCanceled() = stateMachine.canceled

    private fun postEvent(e: CallEvent) {
      events.put(e)

      onNextEvent?.invoke()
      onNextEvent = null
    }

    private fun takeEvent(): CallEvent {
      taskFaker.runTasks() // Run any queued async work first.
      return events.take()
    }

    override fun onRecords(
      call: Dns.Call,
      last: Boolean,
      records: List<Dns.Record>,
    ) {
      check(call == this)
      check(acceptCallbacks) { "unexpected callback" }

      acceptCallbacks = false
      try {
        postEvent(OnRecords(last, records))
      } finally {
        acceptCallbacks = true
      }
    }

    override fun onFailure(
      call: Dns.Call,
      e: IOException,
    ) {
      check(call == this)
      check(acceptCallbacks) { "unexpected callback" }

      acceptCallbacks = false
      try {
        postEvent(OnFailure(e))
      } finally {
        acceptCallbacks = true
      }
    }

    /**
     * Asserts that the next-posted event is a call to [Dns.Callback.onRecords] with a list of IP
     * addresses.
     */
    fun takeOnRecordsIpAddresses(
      last: Boolean = false,
      addresses: List<InetAddress>,
    ): OnRecords {
      val event = takeEvent() as OnRecords
      assertThat(event.last).isEqualTo(last)
      assertThat(event.records.map { (it as Dns.Record.IpAddress).address })
        .isEqualTo(addresses)
      return event
    }

    /**
     * Asserts that the next-posted event is a call to [Dns.Callback.onRecords] with service metadata.
     */
    fun takeOnRecordsServiceMetadata(
      last: Boolean = false,
      alpnIds: List<Protocol>? = null,
      echConfigList: ByteString? = null,
    ): OnRecords {
      val event = takeEvent() as OnRecords
      assertThat(event.last).isEqualTo(last)

      val serviceMetadata = event.records.single() as Dns.Record.ServiceMetadata
      assertThat(serviceMetadata.alpnIds).isEqualTo(alpnIds)
      assertThat(serviceMetadata.echConfigList).isEqualTo(echConfigList)
      return event
    }

    /** Asserts that the next-posted event is a call to [Dns.Callback.onFailure]. */
    fun takeOnFailure(message: String): OnFailure {
      val event = takeEvent() as OnFailure
      assertThat(event.e).hasMessage(message)
      return event
    }
  }

  class Query(
    val question: Question,
  )

  sealed interface TransportEvent {
    class QueryEnqueued(
      val query: Query,
      val callback: Transport.Callback<Query>,
    ) : TransportEvent {
      val hostname: String
        get() = query.question.name
      val type: Int
        get() = query.question.type

      /** Respond to a [TYPE_HTTPS] query with service metadata. */
      fun respondServiceMetadata(
        timeToLive: Int = 300,
        alpnIds: List<String>? = null,
        echConfigList: ByteString? = null,
      ) {
        callback.onResponse(
          dnsResponse(
            query(query.question),
            listOf(
              ResourceRecord.Https(
                name = query.question.name,
                timeToLive = timeToLive,
                alpnIds = alpnIds,
                echConfigList = echConfigList,
              ),
            ),
          ),
        )
      }

      /** Respond to any query with a failure. */
      fun respondFailure(message: String) {
        callback.onFailure(IOException(message))
      }

      /** Respond to a [TYPE_A] or [TYPE_AAAA] query with a (possibly-empty) list of IP addresses. */
      fun respondIpAddresses(
        timeToLive: Int = 300,
        addresses: List<InetAddress> = listOf(),
      ) {
        callback.onResponse(
          dnsResponse(
            query(query.question),
            addresses.map { address ->
              ResourceRecord.IpAddress(
                name = query.question.name,
                timeToLive = timeToLive,
                address = address,
              )
            },
          ),
        )
      }
    }

    class QueryCanceled(
      val query: Query,
    ) : TransportEvent {
      val hostname: String
        get() = query.question.name
      val type: Int
        get() = query.question.type
    }
  }

  sealed interface CallEvent {
    class OnRecords(
      val last: Boolean,
      val records: List<Dns.Record>,
    ) : CallEvent

    class OnFailure(
      val e: IOException,
    ) : CallEvent
  }
}
