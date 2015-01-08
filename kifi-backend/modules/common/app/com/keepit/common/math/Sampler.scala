package com.keepit.common.math

import java.util.Arrays

import scala.util.Random

trait Sampler[A] {
  val sampleSize: Int
  def put(outcome: A, probability: Double)
}

object Sampler {

  val segmentSize: Int = 2000

  def apply[A](sampleSize: Int, out: (A, Int) => Unit) = {
    require(sampleSize > 0, "the sample size must be greater than 0")

    if (sampleSize <= segmentSize) new SimpleSampler[A](sampleSize, out)
    else new CombinedSampler[A](sampleSize, out)
  }

  private[math] def randomize(rnd: Array[Double], base: Double, width: Double): Unit = {
    var i = 0
    while (i < rnd.length) {
      rnd(i) = Random.nextDouble() * width + base
      i += 1
    }
    Arrays.sort(rnd)
  }
}

private[math] class SimpleSampler[A](val sampleSize: Int, out: (A, Int) => Unit) extends Sampler[A] {
  import Sampler._

  require(sampleSize > 0, "the sample size must be greater than 0")

  private[this] var sampledCount: Int = 0

  private[this] val rnd: Array[Double] = new Array[Double](sampleSize)

  randomize(rnd, 0.0, 1.0)

  private[this] var cumulative: Double = 0.0

  def put(outcome: A, probability: Double): Unit = {
    cumulative += probability
    val lastCount = sampledCount

    while (sampledCount < sampleSize && cumulative > rnd(sampledCount)) {
      sampledCount += 1
    }

    if (sampledCount > lastCount) out(outcome, sampledCount - lastCount)
  }
}

private[this] class CombinedSampler[A](val sampleSize: Int, out: (A, Int) => Unit) extends Sampler[A] {
  import Sampler._

  require(sampleSize > 0, "the sample size must be greater than 0")

  private[this] var sampledCount: Int = 0

  private[this] val numSegments = sampleSize - 1 / segmentSize

  private[this] val segmentedSampler = new SegmentedSampler[A](numSegments, (_, c) => { sampledCount += c })
  private[this] val simpleSampler = new SimpleSampler[A](sampleSize - segmentedSampler.sampleSize, (_, c) => { sampledCount += c })

  require(simpleSampler.sampleSize <= segmentSize, "the sample size for SimpleSampler is too big")

  def put(outcome: A, probability: Double): Unit = {
    val lastCount = sampledCount
    simpleSampler.put(outcome, probability)
    segmentedSampler.put(outcome, probability)
    if (sampledCount > lastCount) out(outcome, sampledCount - lastCount)
  }
}

private[math] class SegmentedSampler[A](numSegments: Int, out: (A, Int) => Unit) extends Sampler[A] {
  import Sampler._

  require(numSegments > 0, "the number of segments must be greater than 0")

  val sampleSize = segmentSize * numSegments

  private[this] var sampledCount: Int = 0

  private[this] val rnd: Array[Double] = new Array[Double](segmentSize)
  private[this] val segWidth = 1.0 / numSegments
  private[this] var segSampledCount: Int = 0
  private[this] var segNo = 0

  randomize(rnd, 0.0, segWidth)

  private[this] var cumulative: Double = 0.0

  def put(outcome: A, probability: Double): Unit = {
    cumulative += probability
    val lastCount = sampledCount

    while (segNo < numSegments && cumulative > rnd(segSampledCount)) {
      sampledCount += 1
      if (segSampledCount % segmentSize == 0) {
        segNo += 1
        segSampledCount = 0
        if (segNo < numSegments) Sampler.randomize(rnd, segWidth * segNo, segWidth)
      }
    }

    if (sampledCount > lastCount) out(outcome, sampledCount - lastCount)
  }
}
