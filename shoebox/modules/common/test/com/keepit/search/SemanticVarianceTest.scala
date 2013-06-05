package com.keepit.search

import org.specs2.mutable.Specification

class SemanticVarianceTest extends Specification {
  "SemanticVariance Object" should {
    "compute existence variance" in {
      val existCnt = List(1,2)
      val totalCnt = 3
      val (p1,p2) = ( 1.0f/3, 2.0f/3)
      val correctVar = p1 * (1-p1) + p2 * (1-p2)
      assert( math.abs(SemanticVariance.existenceVariance(existCnt, totalCnt) - correctVar) < 1e-3 )
    }
  }
}
