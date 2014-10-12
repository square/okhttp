package com.squareup.okhttp;

public interface RequestInterceptor {
  Request execute(Request request);
}
