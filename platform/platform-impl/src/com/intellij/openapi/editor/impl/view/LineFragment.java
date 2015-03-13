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
package com.intellij.openapi.editor.impl.view;

import java.awt.*;

/**
 * A building block of text line layout, that knows how to draw itself, and convert between offset, column and x coordinate within itself.
 */
interface LineFragment {
  int getLength();

  int getColumnCount(float startX);

  // offset and column are logical
  int offsetToColumn(float startX, int offset);

  // offset and column are logical
  int columnToOffset(float startX, int column);

  // column is visual
  float columnToX(float startX, int column);

  // column is visual
  int xToColumn(float startX, float x);

  // offsets are visual
  float offsetToX(float startX, int startOffset, int offset);

  // offsets are visual
  void draw(Graphics2D g, float x, float y, int startOffset, int endOffset);
}
