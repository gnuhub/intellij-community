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
package com.intellij.util.containers;

import com.intellij.openapi.util.Condition;
import com.intellij.openapi.util.Pair;
import com.intellij.util.UnmodifiableIterator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * A pruned version of com.google.common.collect.TreeTraverser.
 *
 * Views elements of a type {@code T} as nodes in a tree, and provides methods to traverse the trees
 * induced by this traverser.
 *
 * <p>For example, the tree
 *
 * <pre>          {@code
 *          h
 *        / | \
 *       /  e  \
 *      d       g
 *     /|\      |
 *    / | \     f
 *   a  b  c       }</pre>
 *
 * <p>can be iterated over in preorder (hdabcegf), postorder (abcdefgh), or breadth-first order
 * (hdegabcf).
 *
 * <p>Null nodes are strictly forbidden.
 *
 * @author Louis Wasserman
 */
public abstract class TreeTraverser<T> {

  /**
   * Returns the children of the specified node.  Must not contain null.
   */
  @NotNull
  public abstract Iterable<T> children(@NotNull T root);

  /**
   * Returns an unmodifiable iterable over the nodes in a tree structure, using pre-order
   * traversal. That is, each node's subtrees are traversed after the node itself is returned.
   *
   * <p>No guarantees are made about the behavior of the traversal when nodes change while
   * iteration is in progress or when the iterators generated by {@link #children} are advanced.
   */
  @NotNull
  public final FluentIterable<T> preOrderTraversal(@NotNull final T root) {
    return new FluentIterable<T>() {
      @Override
      public Iterator<T> iterator() {
        return new PreOrderIterator(root);
      }
    };
  }

  /**
   * @see #preOrderTraversal(T)
   */
  @NotNull
  public final FluentIterable<T> preOrderTraversal(@NotNull final Iterable<? extends T> roots) {
    return new FluentIterable<T>() {
      @Override
      public Iterator<T> iterator() {
        return new PreOrderIterator((Iterable<T>)roots);
      }
    };
  }

  /**
   * Returns an unmodifiable iterable over the nodes in a tree structure, using post-order
   * traversal. That is, each node's subtrees are traversed before the node itself is returned.
   * <p/>
   * <p>No guarantees are made about the behavior of the traversal when nodes change while
   * iteration is in progress or when the iterators generated by {@link #children} are advanced.
   */
  @NotNull
  public final FluentIterable<T> postOrderTraversal(@NotNull final T root) {
    return new FluentIterable<T>() {
      @Override
      public Iterator<T> iterator() {
        return new PostOrderIterator(root);
      }
    };
  }

  /**
   * Returns an unmodifiable iterable over the nodes in a tree structure, using breadth-first
   * traversal. That is, all the nodes of depth 0 are returned, then depth 1, then 2, and so on.
   * <p/>
   * <p>No guarantees are made about the behavior of the traversal when nodes change while
   * iteration is in progress or when the iterators generated by {@link #children} are advanced.
   */
  @NotNull
  public final FluentIterable<T> breadthFirstTraversal(@NotNull final T root) {
    return new FluentIterable<T>() {
      @Override
      public Iterator<T> iterator() {
        return new BreadthFirstIterator(root);
      }
    };
  }

  private abstract static class Itr<T> extends UnmodifiableIterator<T> {

    Itr() {
      super(null);
    }

  }

  public abstract static class DfsIterator<T> extends Itr<T> {
    final ArrayDeque<Pair<T, Iterator<T>>> stack = new ArrayDeque<Pair<T, Iterator<T>>>();

    @Override
    public boolean hasNext() {
      return !stack.isEmpty();
    }

    @Nullable
    public T parent() {
      Iterator<Pair<T, Iterator<T>>> it = stack.descendingIterator();
      it.next();
      return it.hasNext() ? it.next().first : null;
    }

    @NotNull
    public FluentIterable<T> upwardTraversal() {
      return new FluentIterable<T>() {
        @Override
        public Iterator<T> iterator() {
          final Iterator<Pair<T, Iterator<T>>> iterator = stack.descendingIterator();
          iterator.next();
          return new FilteringIterator<T, T>(new UnmodifiableIterator<T>(null) {
            @Override
            public boolean hasNext() {
              return iterator.hasNext();
            }

            @Override
            public T next() {
              return iterator.next().first;
            }
          }, Condition.NOT_NULL);
        }
      };
    }
  }

  private final class PreOrderIterator extends DfsIterator<T> {

    int doneCount;

    PreOrderIterator(@NotNull T root) {
      stack.addLast(Pair.<T, Iterator<T>>create(null, new SingletonIterator<T>(root)));
    }

    PreOrderIterator(@NotNull Iterable<T> roots) {
      Iterator<T> iterator = roots.iterator();
      if (iterator.hasNext()) {
        stack.addLast(Pair.<T, Iterator<T>>create(null, iterator));
      }
    }

    @Override
    public boolean hasNext() {
      return stack.size() > doneCount;
    }

    @Override
    public T next() {
      Pair<T, Iterator<T>> top;
      while (!(top = stack.getLast()).second.hasNext()) {
        stack.removeLast();
        doneCount--;
      }
      T result = top.second.next();
      if (!top.second.hasNext()) doneCount++;
      Iterator<T> childItr = children(result).iterator();
      stack.addLast(Pair.create(result, childItr));
      if (!childItr.hasNext()) doneCount++;
      return result;
    }
  }

  private final class PostOrderIterator extends DfsIterator<T> {

    PostOrderIterator(T root) {
      stack.addLast(Pair.create(root, children(root).iterator()));
    }

    @Override
    public T next() {
      while (!stack.isEmpty()) {
        Pair<T, Iterator<T>> top = stack.getLast();
        if (top.second.hasNext()) {
          T child = top.second.next();
          stack.addLast(Pair.create(child, children(child).iterator()));
        }
        else {
          stack.removeLast();
          return top.first;
        }
      }
      throw new NoSuchElementException();
    }
  }

  private final class BreadthFirstIterator extends Itr<T> {
    final Deque<T> queue = new ArrayDeque<T>();

    BreadthFirstIterator(@NotNull T root) {
      queue.add(root);
    }

    @Override
    public boolean hasNext() {
      return !queue.isEmpty();
    }

    //@Override
    public T peek() {
      return queue.element();
    }

    @Override
    public T next() {
      T result = queue.remove();
      ContainerUtil.addAll(queue, children(result));
      return result;
    }
  }
}
