package com.keepit.search

import org.specs2.mutable._
import play.api.Play.current
import play.api.libs.json.Json
import play.api.test._
import play.api.test.Helpers._


class LangDetectorTest extends Specification {

  "LangDetector" should {
    "detect English" in {
      LangDetector.detect("This is a sample English text.") === Lang("en")
    }

    "detect Japanese" in {
      LangDetector.detect("これは日本語です。") === Lang("ja")
    }

    "default to English when not detectable" in {
      LangDetector.detect("") === Lang("en")
      LangDetector.detect("!@#$%%^&*") === Lang("en")
    }
  }
}
