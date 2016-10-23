/*
 * Copyright (C) 2016 Square, Inc.
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
package okhttp3.slack;

import java.util.List;
import okhttp3.HttpUrl;

/** See https://api.slack.com/methods/rtm.start. */
public final class RtmStartResponse {
  HttpUrl url;
  Object self;
  Object team;
  List<Object> users;
  List<Object> channels;
  List<Object> groups;
  List<Object> mpims;
  List<Object> ims;
  List<Object> bots;
}
