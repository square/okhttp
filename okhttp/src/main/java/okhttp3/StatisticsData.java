/*
 * Copyright (C) 2017 Square, Inc.
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
package okhttp3;

/**
 * StatisticsData is tracked from the beginning of a new stream through completion.
 */
public class StatisticsData {

  // It is possible for the DNS query timestamps to be populated without the connect timestamps.
  // In this case, either connection coalescing of different hostnames to the same IP address was used,
  // or there was a race between two threads in establishing the connection.

  // DNS query timing
  /// When the DNS Query was initiated.
  public long initiateDNSQueryAtMillis;
  /// When the DNS Query completed.
  public long finishDNSQueryAtMillis;

  // Connection establishment (including security handshake)
  /// When the socket connection was initiated.
  public long initiateConnectAtMillis;
  /// When the socket connection and handshake completed.
  public long finishConnectAtMillis;

  /// The URL requested.
  public HttpUrl url;

  /// The Route chosen to reach the URL
  public Route route;

  // Request and Response times. May be zero on abort.
  /// When the send was initiated (just prior to the first write to the socket.)
  public long initiateSendAtMillis;
  /// When the send was completed (just after the final write to the socket.)
  public long finishSendAtMillis;
  /// When the receive of the headers was completed.
  public long receivedHeadersAtMillis;
  /// When the receive of the body was completed.
  public long receivedBodyAtMillis;

  /// When an abort occurred on this request.
  public long abortAtMillis;        // zero on success

  /// Full byte count of headers sent, including framing overhead.
  public long byteCountHeadersSent;

  /// Full byte count of body sent, including framing overhead.
  public long byteCountBodySent;

  /// Full byte count of headers received, including framing overhead.
  public long byteCountHeadersReceived;

  /// Full byte count of body received, including framing overhead.
  public long byteCountBodyReceived;

  /// The final outbound Request (or Request received.)
  public Request request;

  /// The inbound response (or final Response sent.)
  public Response response;

  /// Whether a new connection was established for the Request.
  public boolean isNewConnection = false;

  /// The connection ID the request & response utilized
  public int connectionID;

  /// Whether this StatisticsData object has been reported.
  public boolean reported = false;

  /**
   * Merge the header data from a second StatisticsData into this one.
   * It is common to gather StatisticsData prior to knowing which stream it belongs to,
   * so once the stream is identified, that data is then merged into the existing StatisticsData.
   *
   * @param otherData The StatisticsData to merge into this one.
   */
  public void mergeHeaderStats(StatisticsData otherData) {
    
    if (initiateDNSQueryAtMillis == 0)
      initiateDNSQueryAtMillis = otherData.initiateDNSQueryAtMillis;

    if (initiateConnectAtMillis == 0)
      initiateConnectAtMillis = otherData.initiateConnectAtMillis;

    if (finishConnectAtMillis == 0)
      finishConnectAtMillis = otherData.finishConnectAtMillis;

    if (initiateSendAtMillis == 0)
      initiateSendAtMillis = otherData.initiateSendAtMillis;

    if (finishSendAtMillis == 0)
      finishSendAtMillis = otherData.finishSendAtMillis;

    if (receivedHeadersAtMillis == 0)
      receivedHeadersAtMillis = otherData.receivedHeadersAtMillis;

    if (abortAtMillis == 0)
      abortAtMillis = otherData.abortAtMillis;

    byteCountHeadersSent += otherData.byteCountHeadersSent;
    byteCountBodySent += otherData.byteCountBodySent;

    byteCountHeadersReceived += otherData.byteCountHeadersReceived;

    if (request == null)
      request = otherData.request;

    // Response generally isn't populated until the very end, but just in case...
    if (response == null)
      response = otherData.response;

    if (url == null)
      url = otherData.url;

    if (route == null)
      route = otherData.route;
  }

  /**
   * Merge the body data from a second StatisticsData into this one.
   * It is common to gather StatisticsData prior to knowing which stream it belongs to,
   * so once the stream is identified, that data is then merged into the existing StatisticsData.
   *
   * @param otherData The StatisticsData to merge into this one.
   */
  public void mergeDataStats(StatisticsData otherData) {

    if (otherData.receivedBodyAtMillis != 0)  // Always take the latest
      receivedBodyAtMillis = otherData.receivedBodyAtMillis;

    if (abortAtMillis == 0)
      abortAtMillis = otherData.abortAtMillis;

    byteCountBodyReceived += otherData.byteCountBodyReceived;
  }

  /**
   * Report this data as completed to the given observer if this data has not been reported previously.
   *
   * @param observer The StatisticsObserver to report to.
   */
  public void reportCompleted(StatisticsObserver observer) {
    if ( ! reported) {
      reported = true;
      if (observer != null)
        observer.streamCompletion(this);
    }
  }

  /**
   * Report this data as completed (but aborted now) to the given observer if this data has not been
   * reported previously.
   *
   * @param observer The StatisticsObserver to report to.
   */
  public void reportAborted(StatisticsObserver observer) {
    reportAborted(observer, System.currentTimeMillis());
  }

  /**
   * Report this data as completed (but aborted at the given time) to the given observer if this data
   * has not been reported previously.
   *
   * @param observer The StatisticsObserver to report to.
   */
  public void reportAborted(StatisticsObserver observer, long abortTime) {
    if ( ! reported) {
      reported = true;
      this.abortAtMillis = abortTime;
      if (observer != null)
        observer.streamCompletion(this);
    }
  }

  /// Allocate a new StatisticsData instance given the time and length of received Headers.
  public static StatisticsData allocateForReceivedHeaders(long frameRecvTime, long byteCountHeaders) {
    StatisticsData statsData = new StatisticsData();
    statsData.receivedHeadersAtMillis = frameRecvTime;
    statsData.byteCountHeadersReceived = byteCountHeaders;
    return statsData;
  }

  /// Allocate a new StatisticsData instance given the time and length of received Body.
  public static StatisticsData allocateForReceivedData(long frameRecvTime, long byteCountBody) {
    StatisticsData statsData = new StatisticsData();
    statsData.receivedBodyAtMillis = frameRecvTime;
    statsData.byteCountBodyReceived = byteCountBody;
    return statsData;
  }
}
