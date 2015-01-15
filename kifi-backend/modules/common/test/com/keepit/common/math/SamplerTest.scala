package com.keepit.common.math

import org.specs2.mutable.Specification
import scala.math.sqrt

class SamplerTest extends Specification {
  private val sampleSize = (2000 * 2.111).toInt
  private val repeat = 100

  "Sampler" should {

    "sample data in accord with an exponential probability distribution" in {
      var counts = (0 until 64).map(i => i -> 0).toMap
      (0 until repeat) foreach { _ =>
        val sampler = Sampler(sampleSize)
        (0 until 64) foreach { i =>
          val cnt = sampler.collect(scala.math.pow(2, -1 - i))
          counts += (i -> (counts(i) + cnt))
        }
      }

      counts.values.sum === sampleSize * repeat

      val sorted = counts.toSeq.sortBy(-_._2)

      sorted.take(4).map(_._1) == (0 until 4)

      var count = sorted.head._2.toDouble
      sorted.drop(1).take(4).foreach { x =>
        val upper = count / 2.0 + count / 10.0
        val lower = count / 2.0 - count / 10.0
        count = count / 2.0

        x._2.toDouble must beLessThan(upper)
        x._2.toDouble must beGreaterThan(lower)
      }
      0 === 0
    }

    "sample data in accord with an even probability distribution " in {
      var counts = (0 until 10).map(i => i -> 0).toMap
      (0 until repeat) foreach { _ =>
        val sampler = Sampler(sampleSize)
        (0 until 10) foreach { i =>
          val cnt = sampler.collect(0.1)
          counts += (i -> (counts(i) + cnt))
        }
      }

      counts.values.sum === sampleSize * repeat

      val avg = (sampleSize * repeat).toDouble / counts.size.toDouble
      val errorSquareSum = counts.values.map { c =>
        val d = (c.toDouble - avg)
        d * d
      }.sum

      val stddev = sqrt(errorSquareSum / counts.size)

      stddev must beLessThan(avg * 0.01)
    }

    "throw an exception when the probability is not zero or positive" in {
      val sampler = Sampler(sampleSize)

      sampler.collect(0.0) === 0
      sampler.collect(Double.NaN) must throwA[IllegalArgumentException]
      sampler.collect(-.01) must throwA[IllegalArgumentException]
    }
  }
}
