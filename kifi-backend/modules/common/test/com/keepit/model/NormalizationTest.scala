package com.keepit.model

import org.specs2.mutable.Specification

class NormalizationTest extends Specification {

  "Normalization companion object" should {
    "initialize correctly" in {
      Normalization.priority.contains(null) === false
    }
  }
}
