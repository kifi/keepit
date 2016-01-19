package com.keepit.common.util

import scala.util.Random

object RandomChoice {
  def choice[T](xs: IndexedSeq[T]): Option[T] = {
    if (xs.isEmpty) None else Some(xs(Random.nextInt(xs.length)))
  }
}
