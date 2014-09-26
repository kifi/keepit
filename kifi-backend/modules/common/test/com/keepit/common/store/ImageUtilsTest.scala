package com.keepit.common.store

import org.specs2.mutable._

class ImageUtilsTest extends Specification {
  "ImageSize" should {
    "parse width x height" in {
      ImageSize("1x1") === ImageSize(1, 1)
      ImageSize("99999x99999") === ImageSize(99999, 99999)
      ImageSize("abc") must throwA[Exception]
    }
  }
}
