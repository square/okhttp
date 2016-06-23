/*
 * Copyright (C) 2014 Square, Inc.
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
package okhttp3;

import java.io.IOException;

/**
 * Observes, modifies, and potentially short-circuits requests going out and the corresponding
<<<<<<< HEAD:okhttp/src/main/java/com/squareup/okhttp/Interceptor.java
 * requests coming back in. Typically interceptors will be used to add, remove, or transform headers
 * on the request or response.
 * 观察，修改以及可能短路的请求输出和响应请求的回来。通常情况下连接器用来添加，移除或者转换请求或者回应的头部信息
=======
 * responses coming back in. Typically interceptors add, remove, or transform headers on the request
 * or response.
>>>>>>> upstream/master:okhttp/src/main/java/okhttp3/Interceptor.java
 */
public interface Interceptor {
  Response intercept(Chain chain) throws IOException;

  interface Chain {
    Request request();

    Response proceed(Request request) throws IOException;

    Connection connection();
  }
}
