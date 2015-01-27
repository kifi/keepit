package com.keepit.common.math

import scala.util.Random

trait Sampler {
  val sampleSize: Int
  def collect(probability: Double): Int
}

object Sampler {

  def apply(sampleSize: Int) = {
    require(sampleSize > 0, "the sample size must be greater than 0")

    new StratifiedSampler(sampleSize)
  }
}

private[math] class StratifiedSampler(val sampleSize: Int) extends Sampler {

  private[this] var sampledCount: Int = 0

  private[this] var cumulative: Double = 0.0

  def collect(probability: Double): Int = {
    if (probability >= 0.0) {
      cumulative += probability
      val lastCount = sampledCount

      while (sampledCount < sampleSize && cumulative > ((sampledCount.toDouble + Random.nextDouble()) / sampleSize)) {
        sampledCount += 1
      }

      sampledCount - lastCount
    } else {
      throw new IllegalArgumentException(s"probability must be zero or positive: $probability")
    }
  }
}
