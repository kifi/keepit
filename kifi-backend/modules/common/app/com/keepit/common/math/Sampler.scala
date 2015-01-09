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

  val segmentSize: Int = 2000

  def apply(sampleSize: Int) = {
    require(sampleSize > 0, "the sample size must be greater than 0")

    if (sampleSize <= segmentSize) new SimpleSampler(sampleSize)
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
    cumulative += probability
    val lastCount = sampledCount

    while (sampledCount < sampleSize && cumulative > rnd(sampledCount)) {
      sampledCount += 1
    }

    sampledCount - lastCount
  }
}

private[this] class CombinedSampler(val sampleSize: Int) extends Sampler {
  import Sampler.segmentSize

  require(sampleSize > 0, "the sample size must be greater than 0")

  private[this] var sampledCount: Int = 0

  private[this] val numSegments = (sampleSize - 1) / segmentSize

  private[this] val segmentedSampler = new SegmentedSampler(numSegments)
  private[this] val simpleSampler = new SimpleSampler(sampleSize - segmentedSampler.sampleSize)

  require(simpleSampler.sampleSize <= segmentSize, "the sample size for SimpleSampler is too big")

  def collect(probability: Double): Int = {
    val lastCount = sampledCount

    sampledCount += simpleSampler.collect(probability)
    sampledCount += segmentedSampler.collect(probability)

    sampledCount - lastCount
  }
}

private[math] class SegmentedSampler(numSegments: Int) extends Sampler {
  import Sampler.segmentSize

  require(numSegments > 0, "the number of segments must be greater than 0")

  val sampleSize = segmentSize * numSegments

  private[this] var sampledCount: Int = 0

  private[this] val rnd: Array[Double] = new Array[Double](segmentSize)
  private[this] val segWidth = 1.0 / numSegments
  private[this] var segSampledCount: Int = 0
  private[this] var segNo = 0

  randomize(rnd, 0.0, segWidth)

  private[this] var cumulative: Double = 0.0

  def collect(probability: Double): Int = {
    cumulative += probability
    val lastCount = sampledCount

    while (segNo < numSegments && cumulative > rnd(segSampledCount)) {
      sampledCount += 1
      segSampledCount += 1
      if (segSampledCount % segmentSize == 0) {
        segNo += 1
        segSampledCount = 0
        if (segNo < numSegments) randomize(rnd, segWidth * segNo, segWidth * (segNo + 1))
      }
    }

    sampledCount - lastCount
  }
}
