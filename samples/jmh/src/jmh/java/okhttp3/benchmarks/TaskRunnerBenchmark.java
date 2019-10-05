/*
 * Copyright (C) 2019 Square, Inc.
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
package okhttp3.benchmarks;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import okhttp3.internal.concurrent.Task;
import okhttp3.internal.concurrent.TaskQueue;
import okhttp3.internal.concurrent.TaskRunner;
import org.openjdk.jmh.Main;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.RunnerException;

@Fork(1)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 5, time = 2)
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class TaskRunnerBenchmark {
  @Param({"100"})
  int executionCount;

  TaskRunner taskRunner = new TaskRunner();
  TaskQueue taskQueue = taskRunner.newQueue();

  @Setup
  public void setup() throws IOException {
  }

  @Benchmark
  public void executeTasks() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    taskQueue.schedule(new Task("task", true) {
      int count = 0;
      @Override public long runOnce() {
        count++;
        if (count == executionCount) {
          latch.countDown();
          return -1L;
        } else {
          return 0L;
        }
      }
    }, 0L);
    latch.await();
  }

  public static void main(String[] args) throws IOException, RunnerException {
    Main.main(new String[] {
        TaskRunnerBenchmark.class.getName()
    });
  }
}
