/*
 * Copyright (C) 2015 Square, Inc.
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
package com.squareup.okhttp.internal.allocations;

import com.squareup.okhttp.ConnectionPool;
import com.squareup.okhttp.internal.Internal;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Each connection can carry a varying number streams, depending on the underlying protocol being
 * used. HTTP/1.x connections can carry either zero or one streams. HTTP/2 connections can carry any
 * number of streams, dynamically configured with {@code SETTINGS_MAX_CONCURRENT_STREAMS}. A
 * connection currently carrying zero streams is an idle stream. We keep it alive because reusing an
 * existing connection is typically faster than establishing a new one.
 *
 * <p>When a single logical call requires multiple streams due to redirects or authorization
 * challenges, we prefer to use the same physical connection for all streams in the sequence. There
 * are potential performance and behavior consequences to this preference. To support this feature,
 * this class separates <i>allocations</i> from <i>streams</i>. An allocation is created by a call,
 * used for one or more streams, and then released. An allocated connection won't be stolen by
 * other calls while a redirect or authorization challenge is being handled.
 *
 * <p>When the maximum concurrent streams limit is reduced, some allocations will be rescinded.
 * Attempting to create new streams on these allocations will fail.
 *
 * <p>Note that an allocation may be released before its stream is completed. This is intended to
 * make bookkeeping easier for the caller: releasing the allocation as soon as the terminal stream
 * has been found. But only complete the stream once its data stream has been exhausted.
 */
public final class Connection {
  private final ConnectionPool pool;
  private final List<StreamAllocationReference> allocations = new ArrayList<>();
  private int allocationLimit = 1;
  private boolean noNewAllocations;

  /** Nanotime that this connection most recently became idle. */
  long idleAt = Long.MAX_VALUE;

  public Connection(ConnectionPool pool) {
    this.pool = pool;
  }

  /**
   * Attempts to reserves an allocation on this connection for a call. Returns null if no
   * allocation is available.
   */
  public StreamAllocation reserve(String name) {
    synchronized (pool) {
      if (noNewAllocations || allocations.size() >= allocationLimit) return null;

      StreamAllocation result = new StreamAllocation();
      allocations.add(new StreamAllocationReference(result, name));
      return result;
    }
  }

  /**
   * Release the reservation on {@code streamAllocation}. If a stream is currently active it may
   * continue to use this connection until it is complete.
   */
  public void release(StreamAllocation streamAllocation) {
    synchronized (pool) {
      if (streamAllocation.released) throw new IllegalStateException("already released");

      streamAllocation.released = true;
      if (streamAllocation.stream == null) {
        remove(streamAllocation);
      }
    }
  }

  private void remove(StreamAllocation streamAllocation) {
    for (int i = 0, size = allocations.size(); i < size; i++) {
      StreamAllocationReference weakReference = allocations.get(i);
      if (weakReference.get() == streamAllocation) {
        allocations.remove(i);

        if (allocations.isEmpty()) {
          idleAt = System.nanoTime();
          // TODO(jwilson): schedule a cleanup thread if allocationLimit == 0.
        }

        return;
      }
    }
    throw new IllegalArgumentException("unexpected allocation: " + streamAllocation);
  }

  /**
   * Prevents new streams from being created on this connection. This is similar to setting the
   * allocation limit to 0, except that this call is permanent.
   */
  public void noNewStreams() {
    synchronized (pool) {
      noNewAllocations = true;
      for (int i = 0; i < allocations.size(); i++) {
        allocations.get(i).rescind();
      }
    }
  }

  /**
   * Sets the maximum number of streams that this connection will carry. Existing streams will not
   * be interrupted, but existing allocations may be prevented from creating new streams.
   */
  public void setAllocationLimit(int allocationLimit) {
    synchronized (pool) {
      if (allocationLimit < 0) throw new IllegalArgumentException();
      this.allocationLimit = allocationLimit;
      for (int i = allocationLimit; i < allocations.size(); i++) {
        allocations.get(i).rescind();
      }
    }
  }

  /**
   * Look through the allocations held by this connection and confirm that they're all still
   * alive. If they aren't, we have a leaked allocation. In which case we prevent this connection
   * from taking new allocations so that it may gracefully shut down.
   */
  public void pruneLeakedAllocations() {
    synchronized (pool) {
      for (Iterator<StreamAllocationReference> i = allocations.iterator(); i.hasNext(); ) {
        StreamAllocationReference reference = i.next();
        if (reference.get() == null) {
          Internal.logger.warning("Call " + reference.name
              + " leaked a connection. Did you forget to close a response body?");
          noNewAllocations = true;
          i.remove();
          if (allocations.isEmpty()) {
            idleAt = System.nanoTime();
            // TODO(jwilson): schedule a cleanup thread if allocationLimit == 0.
          }
        }
      }
    }
  }

  /** Returns the number of allocations currently held. */
  int size() {
    synchronized (pool) {
      return allocations.size();
    }
  }

  /** Links a stream to a connection. */
  public final class StreamAllocation {
    /** True if the call is done with this allocation. */
    private boolean released;

    /**
     * Non-null if a stream is using this allocation. This may be non-null even after the
     * allocation has been released, because the application may continue to read the response body
     * long after redirects and authorization challenges have all been handled.
     */
    private Stream stream;

    /**
     * True if this allocation has been taken away by the connection. The current stream may
     * proceed but further streams need new allocations.
     */
    private boolean rescinded;

    private StreamAllocation() {
    }

    /** Returns a new stream, or null if this allocation has been rescinded. */
    public Stream newStream(String name) {
      synchronized (pool) {
        if (this.stream != null || released) throw new IllegalStateException();
        if (rescinded) return null;
        this.stream = new Stream(name);
        return this.stream;
      }
    }

    public void streamComplete(Stream stream) {
      synchronized (pool) {
        if (stream == null || stream != this.stream) throw new IllegalArgumentException();
        this.stream = null;
        if (released) {
          remove(this);
        }
      }
    }
  }

  private static final class StreamAllocationReference extends WeakReference<StreamAllocation> {
    private final String name;

    public StreamAllocationReference(StreamAllocation streamAllocation, String name) {
      super(streamAllocation);
      this.name = name;
    }

    public void rescind() {
      StreamAllocation streamAllocation = get();
      if (streamAllocation != null) {
        streamAllocation.rescinded = true;
      }
    }
  }

  public static class Stream {
    public final String name;

    public Stream(String name) {
      this.name = name;
    }

    @Override public String toString() {
      return name;
    }
  }
}
