package com.keepit.common.util

case class Paginator(num: Int, size: Int) {
  def itemsToDrop: Int = num * size
  def offset: Int = itemsToDrop
  def limit = size
}

object Paginator {
  def fromStart(size: Int) = Paginator(0, size)
}
