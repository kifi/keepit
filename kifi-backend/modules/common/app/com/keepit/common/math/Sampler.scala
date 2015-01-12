package com.keepit.common.math

import java.util.Arrays

import scala.util.Random

trait Sampler {
  val sampleSize: Int
  def collect(probability: Double): Int

  protected def randomize(rnd: Array[Double], low: Double, high: Double): Unit = {
    var i = 0
    while (i < rnd.length) {
      rnd(i) = Random.nextDouble() * (high - low) + low
      i += 1
    }
    Arrays.sort(rnd)
  }
}

object Sampler {

  val stratumSize: Int = 2000

  def apply(sampleSize: Int) = {
    require(sampleSize > 0, "the sample size must be greater than 0")

    if (sampleSize <= stratumSize) new SimpleSampler(sampleSize)
    else new CombinedSampler(sampleSize)
  }
}

private[math] class SimpleSampler(val sampleSize: Int) extends Sampler {

  require(sampleSize > 0, "the sample size must be greater than 0")

  private[this] var sampledCount: Int = 0

  private[this] val rnd: Array[Double] = new Array[Double](sampleSize)

  randomize(rnd, 0.0, 1.0)

  private[this] var cumulative: Double = 0.0

  def collect(probability: Double): Int = {
    if (probability >= 0.0) {
      cumulative += probability
      val lastCount = sampledCount

      while (sampledCount < sampleSize && cumulative > rnd(sampledCount)) {
        sampledCount += 1
      }

      sampledCount - lastCount
    } else {
      throw new IllegalArgumentException(s"probability must be zero or positive: $probability")
    }
  }
}

private[this] class CombinedSampler(val sampleSize: Int) extends Sampler {
  import Sampler.stratumSize

  require(sampleSize > 0, "the sample size must be greater than 0")

  private[this] var sampledCount: Int = 0

  private[this] val numStratum = (sampleSize - 1) / stratumSize

  private[this] val stratifiedSampler = new StratifiedSampler(numStratum)
  private[this] val simpleSampler = new SimpleSampler(sampleSize - stratifiedSampler.sampleSize)

  require(simpleSampler.sampleSize <= stratumSize, "the sample size for SimpleSampler is too big")

  def collect(probability: Double): Int = {
    val lastCount = sampledCount

    sampledCount += simpleSampler.collect(probability)
    sampledCount += stratifiedSampler.collect(probability)

    sampledCount - lastCount
  }
}

private[math] class StratifiedSampler(numStrata: Int) extends Sampler {
  import Sampler.stratumSize

  require(numStrata > 0, "the number of strata must be greater than 0")

  val sampleSize = stratumSize * numStrata

  private[this] var sampledCount: Int = 0

  private[this] val rnd: Array[Double] = new Array[Double](stratumSize)
  private[this] val segWidth = 1.0 / numStrata
  private[this] var segSampledCount: Int = 0
  private[this] var segNo = 0

  randomize(rnd, 0.0, segWidth)

  private[this] var cumulative: Double = 0.0

  def collect(probability: Double): Int = {
    if (probability >= 0.0) {
      cumulative += probability
      val lastCount = sampledCount

      while (segNo < numStrata && cumulative > rnd(segSampledCount)) {
        sampledCount += 1
        segSampledCount += 1
        if (segSampledCount % stratumSize == 0) {
          segNo += 1
          segSampledCount = 0
          if (segNo < numStrata) randomize(rnd, segWidth * segNo, segWidth * (segNo + 1))
        }
      }

      sampledCount - lastCount
    } else {
      throw new IllegalArgumentException(s"probability must be zero or positive: $probability")
    }
  }
}
