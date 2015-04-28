package com.squareup.okhttp.mockwebserver.exchanges;

public interface Predicate<T> {
  boolean test(T t);
}
