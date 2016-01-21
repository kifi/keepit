package com.keepit.common.util

import org.apache.commons.math3.random.MersenneTwister

object RandomChoice {
  implicit class RandomChoice(prng: MersenneTwister) {
    def choice[T](xs: IndexedSeq[T]): Option[T] = {
      if (xs.isEmpty) None else Some(xs(prng.nextInt(xs.length)))
    }
  }
}
