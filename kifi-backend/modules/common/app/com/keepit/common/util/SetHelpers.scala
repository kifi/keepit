package com.keepit.common.util

object SetHelpers {
  def unions[T](sets: Traversable[Set[T]]): Set[T] = sets.fold(Set.empty)(_ union _)
}
