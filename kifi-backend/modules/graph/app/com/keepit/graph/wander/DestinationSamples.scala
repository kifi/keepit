package com.keepit.graph.wander

import com.keepit.common.math.Sampler
import com.keepit.graph.model.VertexId
import scala.util.Random

object DestinationSamples {
  val random = new Random

  val empty = new DestinationSamples(Array.empty[Long], 0, 0.0)
}

class DestinationSamples(destinations: Array[Long], val size: Int, val totalWeight: Double) {
  private[this] var index = 0

  def hasNext(): Boolean = index < size

  def next(): VertexId = {
    // randomly retrieve a sample
    val i = DestinationSamples.random.nextInt(size - index) + index
    val nextDestination = destinations(i)
    destinations(i) = destinations(index)
    index += 1
    VertexId(nextDestination)
  }
}

class DestinationSamplesBuilder(maxSampleSize: Int, totalWeightEstimate: Double) {
  private[this] var sum = 0.0
  private[this] val sampler = Sampler(maxSampleSize)
  private[this] val samples = new Array[Long](maxSampleSize)
  private[this] var sampleSize = 0

  def add(vertexId: VertexId, weight: Double): Unit = {
    if (weight > 0.0) {
      // the estimated total weight is used, instead of the real total weight, to compute probabilities
      var cnt = sampler.collect(weight / totalWeightEstimate)
      while (cnt > 0) {
        cnt -= 1
        samples(sampleSize) = vertexId.id
        sampleSize += 1
      }
      sum += weight
    }
  }

  def build(): DestinationSamples = {
    if (sum > 0.0) {
      new DestinationSamples(samples, sampleSize, sum)
    } else {
      DestinationSamples.empty
    }
  }
}

