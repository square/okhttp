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
package okhttp3.recipes;

import java.io.IOException;
import java.util.Map;

import com.alibaba.fastjson.JSON;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public final class ParseResponseWithFastjson {
  private final OkHttpClient client = new OkHttpClient();

  public void run() throws Exception {
    Request request = new Request.Builder()
        .url("https://api.github.com/gists/c2a7c39532239ff261be")
        .build();
    Response response = client.newCall(request).execute();
    if (!response.isSuccessful()) throw new IOException("Unexpected code " + response);

    Gist gist = JSON.parseObject(response.body().string(), Gist.class);
    response.body().close();

    for (Map.Entry<String, GistFile> entry : gist.files.entrySet()) {
      System.out.println(entry.getKey());
      System.out.println(entry.getValue().content);
    }
  }

  public static class Gist {
    public Map<String, GistFile> files;
  }

  public static class GistFile {
    public String content;
  }

  public static void main(String... args) throws Exception {
    new ParseResponseWithFastjson().run();
  }
}
