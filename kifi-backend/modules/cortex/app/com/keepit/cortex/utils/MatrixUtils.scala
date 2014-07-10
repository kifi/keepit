package com.keepit.cortex.utils

import scala.math.sqrt

object MatrixUtils {
  def L2Normalize(vec: Array[Float]): Array[Float] = {
    val s = sqrt(vec.map { x => x * x }.sum)
    if (s == 0) vec else vec.map { x => (x / s).toFloat }
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

}
