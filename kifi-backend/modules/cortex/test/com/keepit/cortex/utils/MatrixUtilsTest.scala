package com.keepit.cortex.utils

import org.specs2.mutable.Specification
import math._

class MatrixUtilsTest extends Specification {
  "matrix utils" should {
    "correctly compute min and max" in {
      val data = Seq(Array(1.0, 2.0, 3.0), Array(4.0, 5.0, 6.0), Array(7.0, 8.0, 9.0), Array(10.0, 11.0, 12.0))
      val (min, max) = MatrixUtils.getMinAndMax(data)
      min === Array(1.0, 2.0, 3.0)
      max === Array(10.0, 11.0, 12.0)
    }
  }

  "correctly computes mean and std" in {
    val data = Seq(Array(1.0, -2.0), Array(3.0, 2.0))
    val (mean, std) = MatrixUtils.getMeanAndStd(data)
    mean === Array(2.0, 0.0)
    (abs(std(0) - sqrt(2.0)) < 1e-4) === true
    (abs(std(1) - sqrt(8.0)) < 1e-4) === true
  }

  "compute cosine similarity" in {
    val v = Array(0.0002, -0.00035, 0.0009)
    val w = Array(0.0001, 0.00023, 0.000045)
    val s = MatrixUtils.cosineDistance(v, w)
    val expect = -0.079593
    (abs(s - expect) < 1e-4) === true
  }

  "compute M-distance for diag Gaussian" in {
    val sample = Array(1.0, -1.0)
    val mean = Array(0.0, 0.5)
    val variance = Array(1.0, 0.5)
    val dist = MatrixUtils.MDistanceDiagGaussian(sample, mean, variance)
    val expect = 5.5
    (abs(dist - expect) < 1e-4) === true
  }

  "KL_divergence works" in {
    var qs = Array(0.5, 0.5)
    var ps = Array(0.5, 0.5)
    var kl = MatrixUtils.KL_divergence(qs, ps)
    abs(kl) should be < 1e-4

    qs = Array(0.8, 0.2)
    kl = MatrixUtils.KL_divergence(qs, ps)
    abs(kl - 0.27807) should be < 1e-4
  }

  "entropy works" in {
    val v = Array(0.1, 0.2, 0.7)
    abs(MatrixUtils.entropy(v) - 1.16) should be < 1e-2
  }
}
