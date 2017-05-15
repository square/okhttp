package com.squareup.okhttp.mockwebserver.exchanges;

public interface Supplier<T> {
  T get();
}
