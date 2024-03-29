package com.keepit.cortex.utils

import scala.math._

object MatrixUtils {
  implicit def toFloat(x: Double): Float = x.toFloat
  def L2Normalize(vec: Array[Float]): Array[Float] = {
    var s = 0f
    val n = vec.size
    var i = 0
    while (i < n) {
      s += vec(i) * vec(i)
      i += 1
    }
    s = sqrt(s).toFloat
    if (s == 0.0) vec else vec.map { x => x / s }
  }

  def dot(v: Array[Float], w: Array[Float]): Float = {
    val n = v.size
    assert(n == w.size)
    var i = 0
    var s = 0f
    while (i < n) { s += v(i) * w(i); i += 1 }
    s
  }

  def add(v: Array[Float], w: Array[Float]): Array[Float] = {
    val n = v.size
    assert(n == w.size)
    val rv = new Array[Float](n)
    (0 until n).foreach { j =>
      rv(j) = v(j) + w(j)
    }
    rv
  }

  def cosineDistance(v: Array[Float], w: Array[Float]): Float = {
    val nv = L2Normalize(v)
    val nw = L2Normalize(w)
    dot(nv, nw)
  }

  def average(vecs: Seq[Array[Float]]): Array[Float] = {
    val n = vecs.size
    assume(n > 0)
    val s = vecs.reduce(add)
    s.map { _ / n }
  }

  def weightedAverage(vecs: Seq[Array[Float]], weights: Array[Float]): Array[Float] = {
    require(vecs.size == weights.size, s"sizes do not match: ${vecs.size}, ${weights.size}")
    val n = vecs.size
    assume(n > 0)
    val dim = vecs(0).size
    val res = new Array[Float](dim)
    (0 until n).foreach { i =>
      val v = vecs(i)
      val w = weights(i)
      (0 until dim).foreach { j =>
        res(j) += v(j) * w
      }
    }

    res
  }

  def getMinAndMax(data: Seq[Array[Float]]): (Array[Float], Array[Float]) = {
    assume(data.size > 0)
    val dataSize = data.size
    val dim = data(0).size
    val minArr = new Array[Float](dim)
    val maxArr = new Array[Float](dim)
    Array.copy(data(0), 0, minArr, 0, dim)
    Array.copy(data(0), 0, maxArr, 0, dim)
    (1 until dataSize).foreach { i =>
      (0 until dim).foreach { j =>
        minArr(j) = minArr(j) min data(i)(j)
        maxArr(j) = maxArr(j) max data(i)(j)
      }
    }
    (minArr, maxArr)
  }

  def getMeanAndStd(data: Seq[Array[Float]]): (Array[Float], Array[Float]) = {
    assume(data.size > 1)
    val dim = data(0).size
    val dataSize = data.size
    val dataTranspose = (0 until dim).map { j =>
      (0 until dataSize).map { i => data(i)(j) }
    }
    val tuples = (0 until dim).map { d =>
      val xs = dataTranspose(d)
      val mean = xs.sum / xs.size
      val variance = xs.map { x => (x - mean) * (x - mean) }.sum / (dataSize - 1)
      (mean, math.sqrt(variance).toFloat)
    }
    (tuples.map { _._1 }.toArray, tuples.map { _._2 }.toArray)
  }

  def MDistanceDiagGaussian(sample: Array[Float], mean: Array[Float], variance: Array[Float]) = {
    assume(sample.size == mean.size && mean.size == variance.size)
    val n = sample.size
    var i = 0
    var s = 0.0
    while (i < n) {
      val v = variance(i)
      val diff = sample(i) - mean(i)
      if (v == 0) s += diff * diff
      else s += diff * diff / v
      i += 1
    }
    s
  }

  def weightedMDistanceDiagGaussian(sample: Array[Float], mean: Array[Float], variance: Array[Float], weights: Array[Float]) = {
    assume(sample.size == mean.size && mean.size == variance.size)
    val n = sample.size
    var i = 0
    var s = 0.0
    val epsilon = 1e-6
    while (i < n) {
      val v = variance(i) + epsilon
      val diff = sample(i) - mean(i)
      val r = weights(i) / v
      s += r * diff * diff
      i += 1
    }
    s
  }

  @inline
  def log2(x: Double): Float = (log(x) / log(2.0)).toFloat

  // KL(qs || ps), we don't check input. (divergence of ps from qs)
  def KL_divergence(qs: Array[Float], ps: Array[Float]): Float = {
    assume(qs.size == ps.size)
    val n = qs.size
    val log_q_over_p = new Array[Float](n)
    var i = 0
    while (i < n) {
      log_q_over_p(i) = if (ps(i) > 0.0) log2(qs(i) / ps(i)) else 0f
      i += 1
    }
    dot(qs, log_q_over_p)
  }

  // eps: prevents singularity
  def entropy(v: Array[Float], eps: Float = 1e-10f): Float = {
    require(v.forall(_ > -eps / 2))
    require(abs(v.sum - 1.0) < 1e-2)
    v.map { x => val y = x + eps; -y * log2(y) }.sum
  }

  // frequently used in extracting top 3 topic ids.
  def argmax3(v: Array[Float]): (Int, Int, Int) = {
    require(v.size >= 3, s"cannot call argmax3() with an array of size ${v.size}")
    val Array(first, second, third) = v.zipWithIndex.sortBy(-_._1).take(3).map { _._2 }
    (first, second, third)
  }
}
