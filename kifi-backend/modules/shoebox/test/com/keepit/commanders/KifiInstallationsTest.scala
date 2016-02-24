package com.keepit.commanders

import org.specs2.mutable.Specification

class KifiInstallationsTest extends Specification {
  "KifiInstallations" should {
    "empty state" in {
      val installations = KifiInstallations(Seq.empty)
      installations.isEmpty === true
      installations.exist === false

    }
  }
}
