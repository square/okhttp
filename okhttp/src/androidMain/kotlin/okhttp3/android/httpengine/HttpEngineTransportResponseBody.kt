/*
 * Copyright 2022 Google LLC
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

package okhttp3.android.httpengine;

import androidx.annotation.Nullable;
import okhttp3.MediaType;
import okhttp3.ResponseBody;
import okio.BufferedSource;

abstract class CronetTransportResponseBody extends ResponseBody {

  private final ResponseBody delegate;

  protected CronetTransportResponseBody(ResponseBody delegate) {
    this.delegate = delegate;
  }

  @Nullable
  @Override
  public final MediaType contentType() {
    return delegate.contentType();
  }

  @Override
  public final long contentLength() {
    return delegate.contentLength();
  }

  @Override
  public final BufferedSource source() {
    return delegate.source();
  }

  @Override
  public final void close() {
    delegate.close();
    customCloseHook();
  }

  abstract void customCloseHook();
}
