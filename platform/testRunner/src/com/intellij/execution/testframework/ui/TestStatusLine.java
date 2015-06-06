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
package com.intellij.execution.testframework.ui;

import com.intellij.execution.ExecutionBundle;
import com.intellij.ide.ui.laf.darcula.ui.DarculaProgressBarUI;
import com.intellij.openapi.progress.util.ColorProgressBar;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.SimpleColoredComponent;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.util.ui.UIUtil;

import javax.swing.*;
import javax.swing.plaf.ProgressBarUI;
import java.awt.*;

/**
 * @author yole
 */
public class TestStatusLine extends JPanel {
  private static final SimpleTextAttributes IGNORE_ATTRIBUTES = new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, ColorProgressBar.YELLOW);
  private static final SimpleTextAttributes ERROR_ATTRIBUTES = new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, ColorProgressBar.RED);

  protected final JProgressBar myProgressBar = new MyProgressBar();
  protected final SimpleColoredComponent myState = new SimpleColoredComponent();

  public TestStatusLine() {
    super(new GridLayout(1, 2));
    final JPanel progressPanel = new JPanel(new GridBagLayout());
    add(progressPanel);
    myProgressBar.setMaximum(100);
    myProgressBar.setBorder(null);
    progressPanel.add(myProgressBar, new GridBagConstraints(0, 0, 0, 0, 1, 1, GridBagConstraints.CENTER, GridBagConstraints.HORIZONTAL,
                                                            new Insets(0, 0, 0, 0), 0, 0));
    setStatusColor(ColorProgressBar.GREEN);
    add(myState);
    myState.append(ExecutionBundle.message("junit.runing.info.starting.label"));
  }

  public void formatTestMessage(final int testsTotal,
                                final int finishedTestsCount,
                                final int failuresCount,
                                final int ignoredTestsCount,
                                final Long duration,
                                final long endTime) {
    myState.clear();
    if (testsTotal == 0) return;
    if (duration == null || endTime == 0) {
      myState.append(finishedTestsCount + " of " + getTestsTotalMessage(testsTotal) + (failuresCount + ignoredTestsCount > 0 ? ": " : ""));
      appendFailuresAndIgnores(failuresCount, ignoredTestsCount);
      return;
    }
    String result = "";
    if (finishedTestsCount == testsTotal) {
      if (testsTotal > 1 && (failuresCount == 0 && ignoredTestsCount == 0 || failuresCount == testsTotal || ignoredTestsCount == testsTotal)) {
        result = "All ";
      }
    }
    else {
      result = "Stopped. " + finishedTestsCount + " of ";
    }

    result += getTestsTotalMessage(testsTotal);

    if (failuresCount == 0 && ignoredTestsCount == 0) {
      myState.append(result + " passed");
    }
    else if (failuresCount == finishedTestsCount) {
      myState.append(result + " failed", ERROR_ATTRIBUTES);
    }
    else if (ignoredTestsCount == finishedTestsCount) {
      myState.append(result + " ignored", IGNORE_ATTRIBUTES);
    }
    else {
      myState.append(result + " done: ");
      appendFailuresAndIgnores(failuresCount, ignoredTestsCount);
    }
    myState.append(" - " + StringUtil.formatDuration(duration), SimpleTextAttributes.GRAY_ATTRIBUTES);
  }

  private static String getTestsTotalMessage(int testsTotal) {
    return testsTotal + " test" + (testsTotal > 1 ? "s" : "");
  }

  private void appendFailuresAndIgnores(int failuresCount, int ignoredTestsCount) {
    if (failuresCount > 0) {
      myState.append(failuresCount + " failed", ERROR_ATTRIBUTES);
    }
    if (ignoredTestsCount > 0) {
      if (failuresCount > 0) {
        myState.append(", ", ERROR_ATTRIBUTES);
      }
      myState.append(ignoredTestsCount + " ignored", IGNORE_ATTRIBUTES);
    }
  }

  public void setStatusColor(Color color) {
    myProgressBar.setForeground(color);
  }

  public Color getStatusColor() {
    return myProgressBar.getForeground();
  }

  public void setFraction(double v) {
    int fraction = (int)(v * 100);
    myProgressBar.setValue(fraction);
  }

  public void setText(String progressStatus_text) {
    myState.clear();
    myState.append(progressStatus_text);
  }

  private static class MyProgressBar extends JProgressBar {
    private static final int NATIVE_LAF_HEIGHT = 12;

    @Override
    public void setUI(ProgressBarUI ui) {
      boolean nativeLaf = UIUtil.isUnderWindowsLookAndFeel() || UIUtil.isUnderAquaLookAndFeel() || UIUtil.isUnderGTKLookAndFeel();
      if (nativeLaf) {
        ui = new DarculaProgressBarUI();
      }
      super.setUI(ui);
      if (nativeLaf) {
        setPreferredSize(new Dimension(getPreferredSize().width, NATIVE_LAF_HEIGHT));
      }
    }
  }
}
