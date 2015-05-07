package com.keepit.curator.feedback

import org.specs2.mutable.Specification

class BayesianMultiplierTest extends Specification {
  "Bayesian Multiplier" should {
    "multiply by 1 when heads == tails" in {
      BayesianMultiplier.betaMultiplier(0, 0) === 1f
      BayesianMultiplier.betaMultiplier(1, 1) === 1f
      BayesianMultiplier.betaMultiplier(20, 20) === 1f
    }

    "reflects confidence" in {
      assert(BayesianMultiplier.betaMultiplier(2, 1) > 1f) // heads > tails, boost
      assert(BayesianMultiplier.betaMultiplier(6, 3) > BayesianMultiplier.betaMultiplier(2, 1)) // more concentrated boosting
      assert(BayesianMultiplier.betaMultiplier(60, 30) > BayesianMultiplier.betaMultiplier(6, 3))

      // flip head/tail counts. flip inequality
      assert(BayesianMultiplier.betaMultiplier(1, 2) < 1f)
      assert(BayesianMultiplier.betaMultiplier(3, 6) < BayesianMultiplier.betaMultiplier(1, 2)) // more concentrated penalty
      assert(BayesianMultiplier.betaMultiplier(30, 60) < BayesianMultiplier.betaMultiplier(3, 6))

      1 === 1
    }

    "behaves well in edge cases" in {
      assert(BayesianMultiplier.betaMultiplier(20, 0) > 1.8f)
      assert(BayesianMultiplier.betaMultiplier(100, 0) > 1.9f)
      assert(BayesianMultiplier.betaMultiplier(0, 20) < 0.2f)
      assert(BayesianMultiplier.betaMultiplier(0, 100) < 0.1f)

      1 === 1
    }
  }
}
