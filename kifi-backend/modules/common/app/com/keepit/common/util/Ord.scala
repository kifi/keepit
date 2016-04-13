package com.keepit.common.util

import org.joda.time.DateTime

object Ord {
  implicit val dateTimeOrdering: Ordering[DateTime] = Ordering.fromLessThan(_ isBefore _)
  def ascending[T](implicit ord: Ordering[T]) = ord
  def descending[T](implicit ord: Ordering[T]) = ord.reverse
}
