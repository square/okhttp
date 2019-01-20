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
package okhttp3.testing;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.internal.Throwables;
import org.junit.runner.Description;
import org.junit.runner.Result;
import org.junit.runner.notification.RunListener;

/**
 * A {@link org.junit.runner.notification.RunListener} used to install an aggressive default {@link
 * java.lang.Thread.UncaughtExceptionHandler} similar to the one found on Android. No exceptions
 * should escape from OkHttp that might cause apps to be killed or tests to fail on Android.
 */
public class InstallUncaughtExceptionHandlerListener extends RunListener {

  private Thread.UncaughtExceptionHandler oldDefaultUncaughtExceptionHandler;
  private Description lastTestStarted;
  private final Map<Throwable, String> exceptions = new LinkedHashMap<>();

  @Override public void testRunStarted(Description description) {
    System.err.println("Installing aggressive uncaught exception handler");
    oldDefaultUncaughtExceptionHandler = Thread.getDefaultUncaughtExceptionHandler();
    Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
      StringWriter errorText = new StringWriter(256);
      errorText.append("Uncaught exception in OkHttp thread \"");
      errorText.append(thread.getName());
      errorText.append("\"\n");
      throwable.printStackTrace(new PrintWriter(errorText));
      errorText.append("\n");
      if (lastTestStarted != null) {
        errorText.append("Last test to start was: ");
        errorText.append(lastTestStarted.getDisplayName());
        errorText.append("\n");
      }
      System.err.print(errorText.toString());

      synchronized (exceptions) {
        exceptions.put(throwable, lastTestStarted.getDisplayName());
      }
    });
  }

  @Override public void testStarted(Description description) {
    lastTestStarted = description;
  }

  @Override public void testRunFinished(Result result) throws Exception {
    Thread.setDefaultUncaughtExceptionHandler(oldDefaultUncaughtExceptionHandler);
    System.err.println("Uninstalled aggressive uncaught exception handler");

    synchronized (exceptions) {
      if (!exceptions.isEmpty()) {
        throw Throwables.rethrowAsException(exceptions.keySet().iterator().next());
      }
    }
  }
}
