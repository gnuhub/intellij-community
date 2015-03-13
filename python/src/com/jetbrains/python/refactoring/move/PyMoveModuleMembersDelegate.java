/*
 * Copyright 2000-2014 JetBrains s.r.o.
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
package com.jetbrains.python.refactoring.move;

import com.google.common.collect.Lists;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.refactoring.BaseRefactoringProcessor;
import com.intellij.refactoring.RefactoringBundle;
import com.intellij.refactoring.move.MoveCallback;
import com.intellij.refactoring.move.MoveHandlerDelegate;
import com.intellij.refactoring.util.CommonRefactoringUtil;
import com.intellij.util.IncorrectOperationException;
import com.intellij.util.containers.ContainerUtil;
import com.jetbrains.python.PyBundle;
import com.jetbrains.python.psi.PyUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * @author vlan
 */
public class PyMoveModuleMembersDelegate extends MoveHandlerDelegate {
  @Override
  public boolean canMove(PsiElement[] elements, @Nullable PsiElement targetContainer) {
    for (PsiElement element : elements) {
      if (!PyMoveModuleMemberUtil.isMovableModuleMember(element)) {
        return false;
      }
    }
    return super.canMove(elements, targetContainer);
  }

  @Override
  public void doMove(Project project,
                     PsiElement[] elements,
                     @Nullable PsiElement targetContainer,
                     @Nullable MoveCallback callback) {
    final List<PsiNamedElement> initialElements = Lists.newArrayList();
    for (PsiElement element : elements) {
      final PsiNamedElement e = getElementToMove(element);
      if (e == null) {
        return;
      }
      initialElements.add(e);
    }
    String initialDestination = null;
    if (targetContainer instanceof PsiFile) {
      final VirtualFile virtualFile = ((PsiFile)targetContainer).getVirtualFile();
      if (virtualFile != null) {
        initialDestination = FileUtil.toSystemDependentName(virtualFile.getPath());
      }
    }
    final PyMoveModuleMembersDialog dialog = PyMoveModuleMembersDialog.getInstance(project, initialElements, initialDestination);
    if (!dialog.showAndGet()) {
      return;
    }
    final String destination = dialog.getTargetPath();
    final boolean previewUsages = dialog.isPreviewUsages();
    try {
      final PsiNamedElement[] selectedElements = ContainerUtil.findAllAsArray(dialog.getSelectedTopLevelSymbols(), PsiNamedElement.class);
      final BaseRefactoringProcessor processor = new PyMoveModuleMembersProcessor(project, selectedElements, destination, previewUsages);
      processor.run();
    }
    catch (IncorrectOperationException e) {
      CommonRefactoringUtil.showErrorMessage(RefactoringBundle.message("error.title"), e.getMessage(), null, project);
    }
  }

  @Override
  public boolean tryToMove(@NotNull PsiElement element,
                           @NotNull Project project,
                           @Nullable DataContext dataContext,
                           @Nullable PsiReference reference,
                           @Nullable Editor editor) {
    final PsiNamedElement e = getElementToMove(element);
    if (e != null && PyMoveModuleMemberUtil.isMovableElement(e)) {
      if (PyUtil.isTopLevel(e)) {
        PsiElement targetContainer = null;
        if (editor != null) {
          final Document document = editor.getDocument();
          targetContainer = PsiDocumentManager.getInstance(project).getPsiFile(document);
        }
        doMove(project, new PsiElement[] {e}, targetContainer, null);
      }
      else {
        CommonRefactoringUtil.showErrorHint(project, editor, PyBundle.message("refactoring.move.module.members.error.selection"),
                                            RefactoringBundle.message("error.title"), null);
      }
      return true;
    }
    return false;
  }

  @Nullable
  public static PsiNamedElement getElementToMove(@NotNull PsiElement element) {
    if (element instanceof PsiNamedElement) {
      return (PsiNamedElement)element;
    }
    return null;
  }
}
