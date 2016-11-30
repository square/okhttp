package okhttp3.internal.http2;

// TODO better mechanism, just for testing and discussion
public interface FrameLogger {
    void frameLog(boolean inbound, int streamId, int length, byte type, byte flags);
}
