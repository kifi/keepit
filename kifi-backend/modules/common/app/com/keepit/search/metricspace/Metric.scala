package com.keepit.search.metricspace
import scala.math.sqrt

trait Metric {
  def distance(a: Array[Double], b: Array[Double]): Double
}

class EucledianMetric extends Metric {
  override def distance(a: Array[Double], b: Array[Double]) = {
    require(a.length == b.length)
    val s = (a zip b).foldLeft(0.0)((s, ab) => s + (ab._1 - ab._2)*(ab._1 - ab._2))
    sqrt(s)
  }
}

class Intersection extends Metric {
  override def distance(a: Array[Double], b: Array[Double]) = {
    require(a.length == b.length)
    (a zip b).foldLeft(0.0)((s, ab) => s + (ab._1 min ab._2))
  }
}
