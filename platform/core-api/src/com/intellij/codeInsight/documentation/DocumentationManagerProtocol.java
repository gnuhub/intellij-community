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
package com.intellij.codeInsight.documentation;

import org.jetbrains.annotations.NonNls;

/**
 * The protocols used for links to elements in quick documentation.
 */
public interface DocumentationManagerProtocol {
  /**
   * The protocol used for linking to PSI elements in quick documentation.
   *
   * @see DocumentationManagerUtil
   */
  @NonNls String PSI_ELEMENT_PROTOCOL = "psi_element://";
  @NonNls String DOC_ELEMENT_PROTOCOL = "doc_element://";
}
