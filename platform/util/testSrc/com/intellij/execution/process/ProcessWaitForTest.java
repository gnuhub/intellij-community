/*
 * Copyright 2000-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.execution.process;

import com.intellij.openapi.util.SystemInfo;
import com.intellij.util.Consumer;
import com.intellij.util.concurrency.Semaphore;
import org.junit.Test;

import java.io.File;
import java.io.IOException;

import static org.junit.Assert.assertTrue;

public class ProcessWaitForTest {
  @Test(timeout = 10000)
  public void notification() throws IOException, InterruptedException {
    File jvm = new File(System.getProperty("java.home") + (SystemInfo.isWindows ? "\\bin\\java.exe" : "/bin/java"));
    assertTrue(jvm.canExecute());

    final Semaphore semaphore1 = new Semaphore();
    semaphore1.down();
    Process process1 = new ProcessBuilder(jvm.getPath(), "-help").redirectErrorStream(true).start();
    ProcessWaitFor.attach(process1, new Consumer<Integer>() {
      @Override
      public void consume(Integer exitCode) {
        semaphore1.up();
      }
    });

    final Semaphore semaphore2 = new Semaphore();
    semaphore2.down();
    Process process2 = new ProcessBuilder(jvm.getPath(), "-version").redirectErrorStream(true).start();
    ProcessWaitFor.attach(process2, new Consumer<Integer>() {
      @Override
      public void consume(Integer exitCode) {
        semaphore2.up();
      }
    });

    assertTrue(semaphore1.waitFor(5000));
    assertTrue(semaphore2.waitFor(5000));
  }
}