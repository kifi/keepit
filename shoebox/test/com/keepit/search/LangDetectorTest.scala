package com.keepit.search

import org.junit.runner.RunWith
import org.specs2.mutable._
import org.specs2.runner.JUnitRunner
import play.api.Play.current
import play.api.libs.json.Json
import play.api.test._
import play.api.test.Helpers._


@RunWith(classOf[JUnitRunner])
class LangDetectorTest extends SpecificationWithJUnit {

  "LangDetector" should {
    "detect English" in {
      LangDetector.detect("This is English.") === Lang("en")
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
