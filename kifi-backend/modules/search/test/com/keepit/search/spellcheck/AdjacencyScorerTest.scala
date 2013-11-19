package com.keepit.search.spellcheck

import org.specs2.mutable.Specification

class AdjacencyScorerTest extends Specification {
  "adjacency scorer" should {
    "correctly merge sorted sequences" in {
      val s = new AdjacencyScorer
      var merged = s.merge(Range(1, 101, 2).map{LabeledInteger("a", _)}, Range(2, 102, 2).map(LabeledInteger("b", _)))
      merged.map{_.value}.seq === Range(1, 101).seq

      merged = s.merge(Seq(2, 3, 7).map{LabeledInteger("a", _)}, Seq(2, 5, 7).map{LabeledInteger("b", _)})
      merged.map{_.value}.seq === Seq(2, 2, 3, 5, 7, 7)

      merged = s.merge(Seq(), Seq(2, 5, 7).map{LabeledInteger("b", _)})
      merged.map{_.value}.seq === Seq(2, 5, 7)
    }

    "computes distance between two sorted integer seq" in {
      val (s1, s2) = (Array(1, 1, 5, 9, 100), Array(4, 7, 60))
      val scorer = new AdjacencyScorer
      scorer.distance(s1, s2) === 1
    }
  }
}
