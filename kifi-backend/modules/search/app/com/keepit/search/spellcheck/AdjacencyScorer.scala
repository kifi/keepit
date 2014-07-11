package com.keepit.search.spellcheck

class AdjacencyScorer {

  // distance between two sorted integer sets X and Y, defined by min(abs(x - y)) for all x in X, y in Y
  // if ordered = true, we require x < y. (Useful for term positions)
  def distance(a: Array[Int], b: Array[Int], earlyStopValue: Int, ordered: Boolean = false): Int = {
    assume(a.length != 0 && b.length != 0)
    val (aSize, bSize) = (a.length, b.length)
    var (p, q) = (0, 0)
    var poper = if (a(p) <= b(q)) 0 else 1
    var m = Int.MaxValue
    var prev = 0

    while (p < aSize && q < bSize && m > earlyStopValue) {
      val newPoper = if (a(p) <= b(q)) 0 else 1
      if (newPoper != poper) {
        poper = newPoper
        val dist = if (poper == 0) a(p) - prev else b(q) - prev
        if (dist < m && !(ordered && newPoper == 0)) m = dist
      }
      if (newPoper == 0) { prev = a(p); p += 1 } else { prev = b(q); q += 1 }
    }

    if (p == aSize) {
      val dist = b(q) - prev
      if (dist < m) m = dist
    } else if (q == bSize && !ordered) {
      val dist = a(p) - prev
      if (dist < m) m = dist
    }
    m
  }
}
