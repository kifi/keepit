package com.keepit.common.util

object Ord {
  def descending[T](implicit ord: Ordering[T]) = ord.reverse
}
