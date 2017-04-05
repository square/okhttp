package okhttp3;

public class StatisticsData {

  // All timestamps are in milliseconds since the epoch as returned by System.currentTimeMillis()
  // It is possible for the DNS query timestamps to be populated without the connect timestamps.
  // In this case, either connection coalescing of different hostnames to the same IP address was used,
  // or there was a race between two threads in establishing the connection.

  // Connection establishment
  public long initiateDNSQueryAtMillis;
  public long finishDNSQueryAtMillis;
  public long initiateConnectAtMillis;
  public long finishConnectAtMillis;

  // Request and Response times. May be zero on abort.
  public long initiateSendAtMillis;
  public long finishSendAtMillis;
  public long receivedHeadersAtMillis;
  public long receivedBodyAtMillis;

  public long abortAtMillis;        // zero on success

  public long byteCountHeadersSent;
  public long byteCountBodySent;

  public long byteCountHeadersReceived;
  public long byteCountBodyReceived;

  public Request request;
  public Response response;

  public boolean isNewConnection = false;
  public boolean reported = false;

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
  }

  public void mergeDataStats(StatisticsData otherData) {

    if (otherData.receivedBodyAtMillis != 0)  // Always take the latest
      receivedBodyAtMillis = otherData.receivedBodyAtMillis;

    if (abortAtMillis == 0)
      abortAtMillis = otherData.abortAtMillis;

    byteCountBodyReceived += otherData.byteCountBodyReceived;
  }

  public void reportCompleted(StatisticsObserver observer) {
    if ( ! reported) {
      reported = true;
      if (observer != null)
        observer.streamCompletion(this);
    }
  }

  public void reportAborted(StatisticsObserver observer) {
    reportAborted(observer, System.currentTimeMillis());
  }

  public void reportAborted(StatisticsObserver observer, long abortTime) {
    if ( ! reported) {
      reported = true;
      this.abortAtMillis = abortTime;
      if (observer != null)
        observer.streamCompletion(this);
    }
  }

  public static StatisticsData allocateForReceivedHeaders(long frameRecvTime, long byteCountHeaders) {
    StatisticsData statsData = new StatisticsData();
    statsData.receivedHeadersAtMillis = frameRecvTime;
    statsData.byteCountHeadersReceived = byteCountHeaders;
    return statsData;
  }

  public static StatisticsData allocateForReceivedData(long frameRecvTime, long byteCountBody) {
    StatisticsData statsData = new StatisticsData();
    statsData.receivedBodyAtMillis = frameRecvTime;
    statsData.byteCountBodyReceived = byteCountBody;
    return statsData;
  }
}
