package okhttp3;

import okhttp3.internal.cache.CacheRequest;
import okhttp3.internal.cache.InternalCache;

import javax.annotation.Nullable;
import java.io.Closeable;
import java.io.Flushable;
import java.io.IOException;
import java.util.Iterator;

public abstract class CacheProvider implements Closeable, Flushable {

    @Nullable
    abstract Response get(Request request);

    @Nullable
    abstract CacheRequest put(Response response);

    public abstract void delete() throws IOException;

    /**
     * Deletes all values stored in the cache. In-flight writes to the cache will complete
     * normally, but the corresponding responses will not be stored.
     */
    public abstract void evictAll() throws IOException;

    /**
     * Returns an iterator over the URLs in this cache. This iterator doesn't throw {@code
     * ConcurrentModificationException}, but if new responses are added while iterating, their URLs
     * will not be returned. If existing responses are evicted during iteration, they will be
     * absent (unless they were already returned).
     *
     * <p>The iterator supports {@linkplain Iterator#remove}. Removing a URL from the iterator
     * evicts the corresponding response from the cache. Use this to evict selected responses.
     */
    public abstract Iterator<String> urls() throws IOException;

    public abstract int writeAbortCount();

    public abstract int writeSuccessCount();

    public abstract long size() throws IOException;

    /**
     * Max size of the cache (in bytes).
     */
    public abstract long maxSize();

    public abstract int networkCount();

    public abstract int hitCount();

    public abstract int requestCount();

    abstract InternalCache internalCache();

}
