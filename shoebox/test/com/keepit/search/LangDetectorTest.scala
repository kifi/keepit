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

    "detect Chinese Simplified" in {
      LangDetector.detect("简体中文测试") === Lang("zh-cn")
    }

    "default to English when not detectable" in {
      LangDetector.detect("") === Lang("en")
      LangDetector.detect("!@#$%%^&*") === Lang("en")
    }
  }

  "LangDetector" should {
    "detect short English" in {
      LangDetector.detectShortText("book and shoe") === Lang("en")
      LangDetector.detectShortText("book", Lang("en")) === Lang("en")
    }

    "detect short Chinese" in {
      LangDetector.detectShortText("简体中文测试") === Lang("zh-cn")
    }
  }
}
