/*
 * Copyright 2018 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.cloud.tools.jib;

import com.google.cloud.tools.jib.builder.BuildLogger;
import java.io.Closeable;

/**
 * Times execution intervals. This is only for testing purposes and will be removed before the first
 * release.
 */
public class Timer implements Closeable {

  private final BuildLogger buildLogger;
  private final int depth;

  private String label;
  private long startTime = System.nanoTime();

  public Timer(BuildLogger buildLogger, String label) {
    this(buildLogger, label, 0);
  }

  public Timer(String label) {
    this(
        new BuildLogger() {

          @Override
          public void debug(CharSequence message) {
            System.out.println("DEBUG: " + message);
          }

          @Override
          public void info(CharSequence message) {
            System.out.println("INFO: " + message);
          }

          @Override
          public void warn(CharSequence message) {
            System.out.println("WARN: " + message);
          }

          @Override
          public void error(CharSequence message) {
            System.out.println("ERROR: " + message);
          }
        },
        label,
        0);
  }

  private Timer(BuildLogger buildLogger, String label, int depth) {
    this.buildLogger = buildLogger;
    this.label = label;
    this.depth = depth;

    if (buildLogger != null) {
      buildLogger.info(getTabs().append("TIMING\t").append(label));
    }
  }

  public Timer subTimer(String label) {
    return new Timer(buildLogger, label, depth + 1);
  }

  public void lap(String label) {
    if (buildLogger != null) {
      buildLogger.info(
          getTabs()
              .append("TIMED\t")
              .append(this.label)
              .append(" : ")
              .append((int) ((System.nanoTime() - startTime) / 1000) / 1000.0)
              .append(" ms"));
    }
    this.label = label;
    startTime = System.nanoTime();
  }

  private StringBuilder getTabs() {
    StringBuilder tabs = new StringBuilder();
    for (int i = 0; i < depth; i++) {
      tabs.append("\t");
    }
    return tabs;
  }

  @Override
  public void close() {
    lap("INVALID");
  }
}
