package com.keepit.common.util

import play.twirl.api.Html

case class Paginator(num: Int, size: Int) {
  def itemsToDrop: Int = num * size
  def offset: Int = itemsToDrop
  def limit = size
}

object Paginator {
  def fromStart(size: Int) = Paginator(0, size)
}

case class PaginationHelper(page: Int, itemCount: Int, pageSize: Int, otherPagesRoute: (Int => Html))
