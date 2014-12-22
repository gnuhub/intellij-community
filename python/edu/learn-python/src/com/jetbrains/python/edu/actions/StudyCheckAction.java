package com.jetbrains.python.edu.actions;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.process.CapturingProcessHandler;
import com.intellij.execution.process.ProcessOutput;
import com.intellij.ide.projectView.ProjectView;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.TaskInfo;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.ui.MessageType;
import com.intellij.openapi.ui.popup.Balloon;
import com.intellij.openapi.ui.popup.BalloonBuilder;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.IdeFocusManager;
import com.intellij.openapi.wm.IdeFrame;
import com.intellij.openapi.wm.WindowManager;
import com.intellij.openapi.wm.ex.StatusBarEx;
import com.intellij.openapi.wm.ex.WindowManagerEx;
import com.jetbrains.python.edu.StudyDocumentListener;
import com.jetbrains.python.edu.StudyState;
import com.jetbrains.python.edu.StudyTestRunner;
import com.jetbrains.python.edu.StudyUtils;
import com.jetbrains.python.edu.course.StudyStatus;
import com.jetbrains.python.edu.course.Task;
import com.jetbrains.python.edu.course.TaskFile;
import com.jetbrains.python.edu.course.TaskWindow;
import com.jetbrains.python.edu.editor.StudyEditor;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.event.HyperlinkEvent;
import javax.swing.event.HyperlinkListener;
import java.awt.*;
import java.io.IOException;
import java.util.List;
import java.util.Map;

public class StudyCheckAction extends DumbAwareAction {

  private static final Logger LOG = Logger.getInstance(StudyCheckAction.class.getName());
  private static final String ANSWERS_POSTFIX = "_answers.py";
  public static final String ACTION_ID = "CheckAction";
  public static final String SHORTCUT = "ctrl alt pressed ENTER";


  private static void flushWindows(@NotNull final Task task, @NotNull final VirtualFile taskDir) {
    for (Map.Entry<String, TaskFile> entry : task.getTaskFiles().entrySet()) {
      String name = entry.getKey();
      TaskFile taskFile = entry.getValue();
      VirtualFile virtualFile = taskDir.findChild(name);
      if (virtualFile == null) {
        continue;
      }
      StudyUtils.flushWindows(taskFile, virtualFile);
    }
  }

  private static void deleteWindowDescriptions(@NotNull final Task task, @NotNull final VirtualFile taskDir) {
    for (Map.Entry<String, TaskFile> entry : task.getTaskFiles().entrySet()) {
      String name = entry.getKey();
      VirtualFile virtualFile = taskDir.findChild(name);
      if (virtualFile == null) {
        continue;
      }
      String windowsFileName = virtualFile.getNameWithoutExtension() + "_windows";
      final VirtualFile windowsFile = taskDir.findChild(windowsFileName);
      if (windowsFile != null) {
        ApplicationManager.getApplication().runWriteAction(new Runnable() {
          @Override
          public void run() {
            StudyUtils.deleteFile(windowsFile);
          }
        });
      }
    }
  }

  private static void drawAllTaskWindows(@NotNull final Project project, @NotNull final Task task, @NotNull final VirtualFile taskDir) {
    for (Map.Entry<String, TaskFile> entry : task.getTaskFiles().entrySet()) {
      String name = entry.getKey();
      TaskFile taskFile = entry.getValue();
      VirtualFile virtualFile = taskDir.findChild(name);
      if (virtualFile == null) {
        continue;
      }
      FileEditor fileEditor = FileEditorManager.getInstance(project).getSelectedEditor(virtualFile);
      if (fileEditor instanceof StudyEditor) {
        StudyEditor studyEditor = (StudyEditor)fileEditor;
        taskFile.drawAllWindows(studyEditor.getEditor());
      }
    }
  }


  public void check(@NotNull final Project project) {
    ApplicationManager.getApplication().runWriteAction(new Runnable() {
      @Override
      public void run() {
        CommandProcessor.getInstance().runUndoTransparentAction(new Runnable() {
          @Override
          public void run() {
            final StudyEditor selectedEditor = StudyEditor.getSelectedStudyEditor(project);
            if (selectedEditor == null) return;
            final StudyState studyState = new StudyState(selectedEditor);
            if (!studyState.isValid()) {
              LOG.error("StudyCheckAction was invokes outside study editor");
              return;
            }
            final IdeFrame frame = ((WindowManagerEx)WindowManager.getInstance()).findFrameFor(project);
            final StatusBarEx statusBar = frame == null ? null : (StatusBarEx)frame.getStatusBar();
            if (statusBar != null) {
              final List<Pair<TaskInfo, ProgressIndicator>> processes = statusBar.getBackgroundProcesses();
              if (!processes.isEmpty()) return;
            }

            final Task task = studyState.getTask();
            final VirtualFile taskDir = studyState.getTaskDir();
            flushWindows(task, taskDir);
            Sdk sdk = StudyUtils.findPythonSdk(project);
            if (sdk == null) {
              createNoPythonInterpreterPopUp(project);
              return;
            }
            final StudyRunAction runAction = (StudyRunAction)ActionManager.getInstance().getAction(StudyRunAction.ACTION_ID);
            if (runAction == null) {
              return;
            }
            runAction.run(project, sdk);
            ApplicationManager.getApplication().invokeLater(new Runnable() {
              @Override
              public void run() {
                IdeFocusManager.getInstance(project).requestFocus(studyState.getEditor().getComponent(), true);
              }
            });
            final StudyTestRunner testRunner = new StudyTestRunner(task, taskDir);
            Process testProcess = null;
            try {
              testProcess = testRunner.createCheckProcess(project, studyState.getVirtualFile().getPath());
            }
            catch (ExecutionException e) {
              LOG.error(e);
            }
            if (testProcess == null) {
              return;
            }
            selectedEditor.getCheckButton().setEnabled(false);
            ProgressManager.getInstance().run(getCheckTask(studyState, testRunner, testProcess, project, selectedEditor));
          }
        });
      }
    });
  }

  @NotNull
  private com.intellij.openapi.progress.Task.Backgroundable getCheckTask(final StudyState studyState,
                                                                         final StudyTestRunner testRunner,
                                                                         final Process testProcess,
                                                                         @NotNull final Project project,
                                                                         final StudyEditor selectedEditor) {
    final Task task = studyState.getTask();
    final VirtualFile taskDir = studyState.getTaskDir();
    final StudyStatus statusBeforeCheck = task.getStatus();
    return new com.intellij.openapi.progress.Task.Backgroundable(project, "Checking task", true) {
      @Override
      public void onSuccess() {
        StudyUtils.updateStudyToolWindow(project);
        drawAllTaskWindows(project, task, taskDir);
        ProjectView.getInstance(project).refresh();
        deleteWindowDescriptions(task, taskDir);
        selectedEditor.getCheckButton().setEnabled(true);
      }

      @Override
      public void onCancel() {
        StudyStatus currentStatus = task.getStatus();
        task.setStatus(statusBeforeCheck, currentStatus);
        deleteWindowDescriptions(task, taskDir);
        selectedEditor.getCheckButton().setEnabled(true);
      }

      @Override
      public void run(@NotNull ProgressIndicator indicator) {
        final Map<String, TaskFile> taskFiles = task.getTaskFiles();
        final CapturingProcessHandler handler = new CapturingProcessHandler(testProcess);
        final ProcessOutput output = handler.runProcessWithProgressIndicator(indicator);
        if (indicator.isCanceled()) {
          ApplicationManager.getApplication().invokeLater(new Runnable() {
            @Override
            public void run() {
              showTestResultPopUp("Tests check cancelled.", MessageType.WARNING.getPopupBackground(), project);
            }
          });
          return;
        }
        final String failedMessage = testRunner.getTestsOutput(output);
        if (StudyTestRunner.TEST_OK.equals(failedMessage)) {
          task.setStatus(StudyStatus.Solved, statusBeforeCheck);
          ApplicationManager.getApplication().invokeLater(new Runnable() {
            @Override
            public void run() {
              showTestResultPopUp("Congratulations!", MessageType.INFO.getPopupBackground(), project);
            }
          });
        }
        else {
          ApplicationManager.getApplication().invokeLater(new Runnable() {
            @Override
            public void run() {
              if (taskDir == null) return;
              task.setStatus(StudyStatus.Failed, statusBeforeCheck);
              for (Map.Entry<String, TaskFile> entry : taskFiles.entrySet()) {
                final String name = entry.getKey();
                final TaskFile taskFile = entry.getValue();
                if (taskFile.getTaskWindows().size() < 2) {
                  taskFile.setStatus(StudyStatus.Failed, StudyStatus.Unchecked);
                  continue;
                }
                CommandProcessor.getInstance().runUndoTransparentAction(new Runnable() {
                  @Override
                  public void run() {
                    ApplicationManager.getApplication().runWriteAction(new Runnable() {
                      @Override
                      public void run() {
                        runSmartTestProcess(taskDir, testRunner, name, taskFile, project);
                      }
                    });
                  }
                });
              }
              showTestResultPopUp(failedMessage, MessageType.ERROR.getPopupBackground(), project);
              navigateToFailedTaskWindow(studyState, task, taskDir, project);
            }
          });
        }
      }
    };
  }

  private static void createNoPythonInterpreterPopUp(@NotNull final Project project) {
    String text = "<html>No Python interpreter configured for the project<br><a href=\"\">Configure interpreter</a></html>";
    BalloonBuilder balloonBuilder =
      JBPopupFactory.getInstance()
        .createHtmlTextBalloonBuilder(text, null, MessageType.WARNING.getPopupBackground(), new HyperlinkListener() {
          @Override
          public void hyperlinkUpdate(HyperlinkEvent event) {
            if (event.getEventType() == HyperlinkEvent.EventType.ACTIVATED) {
              ApplicationManager.getApplication().invokeLater(new Runnable() {
                @Override
                public void run() {
                  ShowSettingsUtil.getInstance()
                    .showSettingsDialog(project, "Project Interpreter");
                }
              });
            }
          }
        });
    balloonBuilder.setHideOnLinkClick(true);
    final Balloon balloon = balloonBuilder.createBalloon();
    showCheckPopUp(project, balloon);
  }

  private static void navigateToFailedTaskWindow(@NotNull final StudyState studyState,
                                                 @NotNull final Task task,
                                                 @NotNull final VirtualFile taskDir,
                                                 @NotNull final Project project) {
    TaskFile selectedTaskFile = studyState.getTaskFile();
    Editor editor = studyState.getEditor();
    TaskFile taskFileToNavigate = selectedTaskFile;
    VirtualFile fileToNavigate = studyState.getVirtualFile();
    if (!selectedTaskFile.hasFailedTaskWindows()) {
      for (Map.Entry<String, TaskFile> entry : task.getTaskFiles().entrySet()) {
        String name = entry.getKey();
        TaskFile taskFile = entry.getValue();
        if (taskFile.hasFailedTaskWindows()) {
          taskFileToNavigate = taskFile;
          VirtualFile virtualFile = taskDir.findChild(name);
          if (virtualFile == null) {
            continue;
          }
          FileEditor fileEditor = FileEditorManager.getInstance(project).getSelectedEditor(virtualFile);
          if (fileEditor instanceof StudyEditor) {
            StudyEditor studyEditor = (StudyEditor)fileEditor;
            editor = studyEditor.getEditor();
          }
          fileToNavigate = virtualFile;
          break;
        }
      }
    }
    FileEditorManager.getInstance(project).openFile(fileToNavigate, true);
    final Editor editorToNavigate = editor;
    ApplicationManager.getApplication().invokeLater(new Runnable() {
      @Override
      public void run() {
        IdeFocusManager.getInstance(project).requestFocus(editorToNavigate.getContentComponent(), true);
      }
    });
    taskFileToNavigate.navigateToFirstFailedTaskWindow(editor);
  }

  private void runSmartTestProcess(@NotNull final VirtualFile taskDir,
                                   @NotNull final StudyTestRunner testRunner,
                                   final String taskFileName,
                                   @NotNull final TaskFile taskFile,
                                   @NotNull final Project project) {
    final TaskFile answerTaskFile = new TaskFile();
    final VirtualFile virtualFile = taskDir.findChild(taskFileName);
    if (virtualFile == null) {
      return;
    }
    final VirtualFile answerFile = getCopyWithAnswers(taskDir, virtualFile, taskFile, answerTaskFile);
    for (final TaskWindow taskWindow : answerTaskFile.getTaskWindows()) {
      final Document document = FileDocumentManager.getInstance().getDocument(virtualFile);
      if (document == null) {
        continue;
      }
      if (!taskWindow.isValid(document)) {
        continue;
      }
      taskWindow.smartCheck(project, answerFile, answerTaskFile, taskFile, testRunner, virtualFile, document);
    }
    StudyUtils.deleteFile(answerFile);
  }

  private VirtualFile getCopyWithAnswers(@NotNull final VirtualFile taskDir,
                                         @NotNull final VirtualFile file,
                                         @NotNull final TaskFile source,
                                         @NotNull final TaskFile target) {
    VirtualFile copy = null;
    try {

      copy = file.copy(this, taskDir, file.getNameWithoutExtension() + ANSWERS_POSTFIX);
      final FileDocumentManager documentManager = FileDocumentManager.getInstance();
      final Document document = documentManager.getDocument(copy);
      if (document != null) {
        TaskFile.copy(source, target);
        StudyDocumentListener listener = new StudyDocumentListener(target);
        document.addDocumentListener(listener);
        for (TaskWindow taskWindow : target.getTaskWindows()) {
          if (!taskWindow.isValid(document)) {
            continue;
          }
          final int start = taskWindow.getRealStartOffset(document);
          final int end = start + taskWindow.getLength();
          final String text = taskWindow.getPossibleAnswer();
          document.replaceString(start, end, text);
        }
        ApplicationManager.getApplication().runWriteAction(new Runnable() {
          @Override
          public void run() {
            documentManager.saveDocument(document);
          }
        });
      }
    }
    catch (IOException e) {
      LOG.error(e);
    }
    return copy;
  }

  private static void showTestResultPopUp(final String text, Color color, @NotNull final Project project) {
    BalloonBuilder balloonBuilder =
      JBPopupFactory.getInstance().createHtmlTextBalloonBuilder(text, null, color, null);
    final Balloon balloon = balloonBuilder.createBalloon();
    showCheckPopUp(project, balloon);
  }

  /**
   * shows pop up in the center of "check task" button in study editor
   */
  private static void showCheckPopUp(@NotNull final Project project, @NotNull final Balloon balloon) {
    StudyEditor studyEditor = StudyEditor.getSelectedStudyEditor(project);
    assert studyEditor != null;
    JButton checkButton = studyEditor.getCheckButton();
    balloon.showInCenterOf(checkButton);
    Disposer.register(project, balloon);
  }

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    Project project = e.getProject();
    if (project != null) {
      check(project);
    }
  }
}
