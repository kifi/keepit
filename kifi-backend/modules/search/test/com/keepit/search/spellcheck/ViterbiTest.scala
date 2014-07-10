package com.keepit.search.spellcheck

import org.specs2.mutable.Specification

class ViterbiTest extends Specification {

  "Viterbi" should {
    "work" in {
      val trellis = Trellis(Array(2, 3, 2))
      val t1 = TransitionScore(Map((0, 0) -> 1f, (0, 1) -> 2f, (0, 2) -> 3f, (1, 0) -> 2f, (1, 1) -> 3f, (1, 2) -> 2))
      val t2 = TransitionScore(Map((0, 0) -> 3f, (0, 1) -> 1f, (1, 0) -> 4f, (1, 1) -> 3f, (2, 0) -> 2f, (2, 1) -> 3f))
      val transitionScores = TransitionScores(Array(t1, t2))
      val v = new Viterbi
      val path = v.solve(trellis, transitionScores)
      path.score === 12f
      path.path === Array(1, 1, 0)
    }

    "work in some edge cases" in {
      var trellis = Trellis(Array(1, 1))
      var score = TransitionScore(Map((0, 0) -> 1f))
      var scores = TransitionScores(Array(score))
      val v = new Viterbi
      var p = v.solve(trellis, scores)
      p.score === 1f
      p.path === Array(0, 0)

      trellis = Trellis(Array(1, 2))
      score = TransitionScore(Map((0, 0) -> 1f, (0, 1) -> 2f))
      scores = TransitionScores(Array(score))
      p = v.solve(trellis, scores)
      p.score === 2f
      p.path === Array(0, 1)
    }
  }

}
