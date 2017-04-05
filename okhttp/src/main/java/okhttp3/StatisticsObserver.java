package okhttp3;

public interface StatisticsObserver {

  // Whenever a stream completes, regardless of success or failure.
  void streamCompletion(StatisticsData statsData);
}
