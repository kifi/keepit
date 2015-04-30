package com.keepit.cortex.utils

import org.specs2.mutable.Specification
import math._

class MatrixUtilsTest extends Specification {
  "matrix utils" should {
    "correctly compute min and max" in {
      val data = Seq(Array(1.0f, 2.0f, 3.0f), Array(4.0f, 5.0f, 6.0f), Array(7.0f, 8.0f, 9.0f), Array(10.0f, 11.0f, 12.0f))
      val (min, max) = MatrixUtils.getMinAndMax(data)
      min === Array(1.0f, 2.0f, 3.0f)
      max === Array(10.0f, 11.0f, 12.0f)
    }
  }

  "correctly computes mean and std" in {
    val data = Seq(Array(1.0f, -2.0f), Array(3.0f, 2.0f))
    val (mean, std) = MatrixUtils.getMeanAndStd(data)
    mean === Array(2.0f, 0.0f)
    (abs(std(0) - sqrt(2.0f)) < 1e-4) === true
    (abs(std(1) - sqrt(8.0f)) < 1e-4) === true
  }

  "compute cosine similarity" in {
    val v = Array(0.0002f, -0.00035f, 0.0009f)
    val w = Array(0.0001f, 0.00023f, 0.000045f)
    val s = MatrixUtils.cosineDistance(v, w)
    val expect = -0.079593f
    (abs(s - expect) < 1e-4) === true
  }

  "compute M-distance for diag Gaussian" in {
    val sample = Array(1.0f, -1.0f)
    val mean = Array(0.0f, 0.5f)
    val variance = Array(1.0f, 0.5f)
    val dist = MatrixUtils.MDistanceDiagGaussian(sample, mean, variance)
    val expect = 5.5f
    (abs(dist - expect) < 1e-4) === true
  }

  "KL_divergence works" in {
    var qs = Array(0.5f, 0.5f)
    var ps = Array(0.5f, 0.5f)
    var kl = MatrixUtils.KL_divergence(qs, ps)
    abs(kl) should be < 1e-4f

    qs = Array(0.8f, 0.2f)
    kl = MatrixUtils.KL_divergence(qs, ps)
    abs(kl - 0.27807f) should be < 1e-4f
  }

  "entropy works" in {
    val v = Array(0.1f, 0.2f, 0.7f)
    abs(MatrixUtils.entropy(v) - 1.16f) should be < 1e-2f
  }
}
