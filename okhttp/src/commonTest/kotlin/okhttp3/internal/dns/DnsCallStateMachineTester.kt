@file:OptIn(OkHttpInternalApi::class)

package okhttp3.internal.dns

import assertk.assertThat
import assertk.assertions.hasMessage
import assertk.assertions.isEqualTo
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.LinkedBlockingDeque
import okhttp3.Dns
import okhttp3.Protocol
import okhttp3.dnsResponse
import okhttp3.internal.OkHttpInternalApi
import okhttp3.internal.dns.DnsCallStateMachineTester.Event.OnRecords
import okhttp3.internal.dns.DnsCallStateMachineTester.Event.QueryEnqueued
import okio.ByteString

/**
 * Test the DNS state machine.
 *
 * This has helpers to operate on the state machine:
 *
 * This tracks all effects from the state machine as events: creating queries, canceling queries,
 * calling callbacks.
 */
fun testDnsCallStateMachine(
  request: Dns.Request,
  includeIPv6: Boolean = true,
  includeServiceMetadata: Boolean = true,
  block: DnsCallStateMachineTester.() -> Unit,
) {
  val tester = DnsCallStateMachineTester(request, includeIPv6, includeServiceMetadata)
  tester.block()
}

class DnsCallStateMachineTester internal constructor(
  request: Dns.Request,
  includeIPv6: Boolean = true,
  includeServiceMetadata: Boolean = true,
) {
  private val events = LinkedBlockingDeque<Event>()
  var onNextEvent: (() -> Unit)? = null

  /** Defend against re-entrant calls. */
  private var acceptCallbacks: Boolean = true

  private val transport =
    object : DnsCallStateMachine.Transport<Query> {
      override fun newQuery(dnsMessage: DnsMessage) = Query(dnsMessage)

      override fun enqueue(query: Query) {
        postEvent(QueryEnqueued(query))
      }

      override fun cancel(query: Query) {
        postEvent(Event.QueryCanceled(query))
      }
    }

  val call: Dns.Call =
    object : Dns.Call {
      override val request: Dns.Request = request

      override fun enqueue(callback: Dns.Callback) {
        stateMachine.start(callback)
      }

      override fun cancel() {
        stateMachine.cancel()
      }

      override fun isCanceled() = stateMachine.canceled
    }

  private val callback =
    object : Dns.Callback {
      override fun onRecords(
        call: Dns.Call,
        last: Boolean,
        records: List<Dns.Record>,
      ) {
        check(call == this@DnsCallStateMachineTester.call)
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
        check(call == this@DnsCallStateMachineTester.call)
        check(acceptCallbacks) { "unexpected callback" }

        acceptCallbacks = false
        try {
          postEvent(Event.OnFailure(e))
        } finally {
          acceptCallbacks = true
        }
      }
    }

  val stateMachine =
    DnsCallStateMachine<Query>(
      transport = transport,
      call = call,
      canceledException = null,
      includeIPv6 = includeIPv6,
      includeServiceMetadata = includeServiceMetadata,
    )

  /** Start the DNS call. */
  fun enqueue() {
    check(acceptCallbacks) { "unexpected enqueue" }

    acceptCallbacks = false
    try {
      call.enqueue(callback)
    } finally {
      acceptCallbacks = true
    }
  }

  private fun postEvent(e: Event) {
    events.put(e)

    onNextEvent?.invoke()
    onNextEvent = null
  }

  /** Asserts that the next-posted event is a query enqueue. */
  fun takeQuery(
    hostname: String,
    type: Int,
  ): QueryEnqueued {
    val event = events.take() as QueryEnqueued
    assertThat(event.hostname).isEqualTo(hostname)
    assertThat(event.type).isEqualTo(type)
    return event
  }

  /** Asserts that the next-posted event is a query cancel. */
  fun takeCancel(
    hostname: String,
    type: Int,
  ): Event.QueryCanceled {
    val event = events.take() as Event.QueryCanceled
    assertThat(event.hostname).isEqualTo(hostname)
    assertThat(event.type).isEqualTo(type)
    return event
  }

  /** Respond to a [TYPE_A] or [TYPE_AAAA] query with a (possibly-empty) list of IP addresses. */
  fun respondIpAddresses(
    query: Query,
    timeToLive: Int = 300,
    addresses: List<InetAddress> = listOf(),
  ) {
    stateMachine.onQueryResponse(
      query,
      dnsResponse(
        query.dnsMessage,
        addresses.map { address ->
          ResourceRecord.IpAddress(
            name =
              query.dnsMessage.questions
                .single()
                .name,
            timeToLive = timeToLive,
            address = address,
          )
        },
      ),
    )
  }

  /** Respond to a [TYPE_HTTPS] query with service metadata. */
  fun respondServiceMetadata(
    query: Query,
    timeToLive: Int = 300,
    alpnIds: List<String>? = null,
    echConfigList: ByteString? = null,
  ) {
    stateMachine.onQueryResponse(
      query,
      dnsResponse(
        query.dnsMessage,
        listOf(
          ResourceRecord.Https(
            name =
              query.dnsMessage.questions
                .single()
                .name,
            timeToLive = timeToLive,
            alpnIds = alpnIds,
            echConfigList = echConfigList,
          ),
        ),
      ),
    )
  }

  /** Respond to any query with a failure. */
  fun respondFailure(
    query: Query,
    e: IOException,
  ) {
    stateMachine.onQueryFailure(query, e)
  }

  /**
   * Asserts that the next-posted event is a call to [Dns.Callback.onRecords] with a list of IP
   * addresses.
   */
  fun takeOnRecordsIpAddresses(
    last: Boolean = false,
    addresses: List<InetAddress>,
  ): OnRecords {
    val event = events.take() as OnRecords
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
    val event = events.take() as OnRecords
    assertThat(event.last).isEqualTo(last)

    val serviceMetadata = event.records.single() as Dns.Record.ServiceMetadata
    assertThat(serviceMetadata.alpnIds).isEqualTo(alpnIds)
    assertThat(serviceMetadata.echConfigList).isEqualTo(echConfigList)
    return event
  }

  /** Asserts that the next-posted event is a call to [Dns.Callback.onFailure]. */
  fun takeOnFailure(message: String): Event.OnFailure {
    val event = events.take() as Event.OnFailure
    assertThat(event.e).hasMessage(message)
    return event
  }

  class Query(
    val dnsMessage: DnsMessage,
  )

  sealed interface Event {
    data class QueryEnqueued(
      val query: Query,
    ) : Event {
      val hostname: String
        get() =
          query.dnsMessage.questions
            .single()
            .name
      val type: Int
        get() =
          query.dnsMessage.questions
            .single()
            .type
    }

    data class QueryCanceled(
      val query: Query,
    ) : Event {
      val hostname: String
        get() =
          query.dnsMessage.questions
            .single()
            .name
      val type: Int
        get() =
          query.dnsMessage.questions
            .single()
            .type
    }

    data class OnRecords(
      val last: Boolean,
      val records: List<Dns.Record>,
    ) : Event

    data class OnFailure(
      val e: IOException,
    ) : Event
  }
}
