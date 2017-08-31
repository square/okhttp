package okhttp3;

public interface DiskCacheListener {
    void onStart(String url, long totalCount);
    void onProgress(String url, long totalCount, long writeCount);
    void onComplete(String url);
}