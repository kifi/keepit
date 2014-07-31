package com.keepit.cortex.utils

import org.specs2.mutable.Specification
import math._

class MatrixUtilsTest extends Specification {
  "matrix utils" should {
    "correctly compute min and max" in {
      val data = Seq(Array(1f, 2f, 3f), Array(4f, 5f, 6f), Array(7f, 8f, 9f), Array(10f, 11f, 12f))
      val (min, max) = MatrixUtils.getMinAndMax(data)
      min === Array(1f, 2f, 3f)
      max === Array(10f, 11f, 12f)
    }
  }

  "correctly computes mean and std" in {
    val data = Seq(Array(1f, -2f), Array(3f, 2f))
    val (mean, std) = MatrixUtils.getMeanAndStd(data)
    mean === Array(2f, 0f)
    std === Array(sqrt(2).toFloat, sqrt(8).toFloat)
  }

  "compute cosine similarity" in {
    val v = Array(0.0002f, -0.00035f, 0.0009f)
    val w = Array(0.0001f, 0.00023f, 0.000045f)
    val s = MatrixUtils.cosineDistance(v, w)
    val expect = -0.079593f
    (abs(s - expect) < 1e-3) === true
  }
}
