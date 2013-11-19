package com.keepit.search.spellcheck


case class LabeledInteger(label: String, value: Int) {
  def < (that: LabeledInteger) = this.value < that.value
  def == (that: LabeledInteger) = this.value == that.value
  def <= (that: LabeledInteger) = this < that || this == that
}

class AdjacencyScorer {

  def merge(a: Seq[LabeledInteger], b: Seq[LabeledInteger]): Seq[LabeledInteger] = {
    if (a.isEmpty || b.isEmpty) a ++ b
    else {
      if (a.head < b.head) {
        val (left, right) = a.partition(_ < b.head)
        left ++ merge(right, b)
      } else {
        val (left, right) = b.partition(_ <= a.head)
        left ++ merge(a, right)
      }
    }
  }

  // distance between two sorted integer sets X and Y, defined by min(abs(x - y)) for all x in X, y in Y
  def distance(pos1: Array[Int], pos2: Array[Int]): Int = {
    assume(pos1.length != 0 && pos2.length != 0)

    val mixed = merge(pos1.map{ p => LabeledInteger("a", p)}, pos2.map{ p => LabeledInteger("b", p)})
    mixed.toArray.sliding(2, 1)
    .filter{ case Array(a, b) => a.label != b.label }
    .map{ case Array(a, b) => b.value - a.value }
    .foldLeft(Int.MaxValue)(_ min _)
  }

}
