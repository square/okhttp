package com.squareup.okhttp;

public interface ResponseInterceptor {
  Response execute(Response response);
}
