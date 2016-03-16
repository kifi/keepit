package com.keepit.common.util

import org.apache.commons.math3.random.MersenneTwister

object RandomChoice {
  implicit class RandomChoice(prng: MersenneTwister) {
    def choiceOption[T](xs: IndexedSeq[T]): Option[T] = {
      if (xs.isEmpty) None else Some(xs(prng.nextInt(xs.length)))
    }
    def choice[T](xs: IndexedSeq[T]): T = choiceOption(xs).getOrElse(throw new NoSuchElementException)
  }
}
