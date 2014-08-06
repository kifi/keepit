package com.keepit.cortex.utils

import scala.math.sqrt

object MatrixUtils {
  implicit def toDoubleArray(vec: Array[Float]): Array[Double] = vec.map { _.toDouble }
  implicit def toFloatArray(vec: Array[Double]): Array[Float] = vec.map { _.toFloat }
  implicit def toFloat(x: Double): Float = x.toFloat

  def L2Normalize(vec: Array[Double]): Array[Double] = {
    var s = 0.0
    vec.foreach { x => s += x * x }
    s = sqrt(s)
    if (s == 0.0) vec else vec.map { x => x / s }
  }

  def dot(v: Array[Double], w: Array[Double]): Double = {
    val n = v.size
    assert(n == w.size)
    var i = 0
    var s = 0.0
    while (i < n) { s += v(i) * w(i); i += 1 }
    s
  }

  def add(v: Array[Double], w: Array[Double]): Array[Double] = {
    val n = v.size
    assert(n == w.size)
    val rv = new Array[Double](n)
    (0 until n).foreach { j =>
      rv(j) = v(j) + w(j)
    }
    rv
  }

  def cosineDistance(v: Array[Double], w: Array[Double]): Double = {
    val nv = L2Normalize(v)
    val nw = L2Normalize(w)
    dot(nv, nw)
  }

  def average(vecs: Seq[Array[Double]]): Array[Double] = {
    val n = vecs.size
    assume(n > 0)
    val s = vecs.reduce(add)
    s.map { _ / n }
  }

  def weightedAverage(vecs: Seq[Array[Double]], weights: Array[Double]): Array[Double] = {
    val n = vecs.size
    assume(n > 0)
    val dim = vecs(0).size
    val res = new Array[Double](dim)
    (0 until n).foreach { i =>
      val v = vecs(i)
      val w = weights(i)
      (0 until dim).foreach { j =>
        res(j) += v(j) * w
      }
    }

    res
  }

  def getMinAndMax(data: Seq[Array[Double]]): (Array[Double], Array[Double]) = {
    assume(data.size > 0)
    val dataSize = data.size
    val dim = data(0).size
    val minArr = new Array[Double](dim)
    val maxArr = new Array[Double](dim)
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

  def getMeanAndStd(data: Seq[Array[Double]]): (Array[Double], Array[Double]) = {
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

  def MDistanceDiagGaussian(sample: Array[Double], mean: Array[Double], variance: Array[Double]) = {
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

}
