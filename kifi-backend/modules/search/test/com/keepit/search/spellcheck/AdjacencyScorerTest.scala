package com.keepit.search.spellcheck

import org.specs2.mutable.Specification

class AdjacencyScorerTest extends Specification {
  "adjacency scorer" should {
    "computes distance between two sorted integer seq" in {
      var (s1, s2) = (Array(1, 1, 5, 9, 100), Array(4, 7, 60))
      val scorer = new AdjacencyScorer
      scorer.distance(s1, s2, earlyStopValue = 1, false) === 1

      s1 = Array(1); s2 = Array(4)
      scorer.distance(s1, s2, earlyStopValue = 1, false) === 3

      s1 = Array(12); s2 = Array(4, 7)
      scorer.distance(s1, s2, earlyStopValue = 1, false) === 5

      s1 = Range(1, 100, 4).toArray; s2 = Array(39, 63, 72)
      scorer.distance(s1, s2, earlyStopValue = 1, false) === 1
    }

    "work if ordered = true" in {
      var (s1, s2) = (Array(1, 1, 5, 9, 100), Array(4, 7, 60))
      val scorer = new AdjacencyScorer
      scorer.distance(s1, s2, earlyStopValue = 1, ordered = true) === 2

      s1 = Array(1); s2 = Array(4)
      scorer.distance(s1, s2, earlyStopValue = 1, ordered = true) === 3
      s1 = Array(12); s2 = Array(4, 7)
      scorer.distance(s1, s2, earlyStopValue = 1, ordered = true) === Int.MaxValue

      s1 = Range(1, 100, 4).toArray; s2 = Array(39, 63, 72)
      scorer.distance(s1, s2, earlyStopValue = 1, ordered = true) === 2
    }
  }
}
