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

    "detect German" in {
      LangDetector.detect("Dies ist ein Beispiel deutsche Text.") === Lang("de")
    }

    "detect Japanese" in {
      LangDetector.detect("この文は、どこから見てもまちがいなく日本語だといえます。") === Lang("ja")
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
      LangDetector.detectShortText("Amazon", Map(Lang("en") -> 0.9d)) === Lang("en")
      LangDetector.detectShortText("pandora", Map(Lang("en") -> 0.9d)) === Lang("en")
      LangDetector.detectShortText("make me happier", Map(Lang("en") -> 0.98d)) === Lang("en")
      LangDetector.detectShortText("make you happy", Map(Lang("en") -> 0.99999d)) === Lang("en")
    }

    "detect short Chinese" in {
      LangDetector.detectShortText("简体中文测试") === Lang("zh-cn")
    }

    "detect short Japanese" in {
      LangDetector.detectShortText("サッカー", Map(Lang("en") -> 0.66d)) === Lang("ja")
      LangDetector.detectShortText("料理", Map(Lang("en") -> 0.20d, Lang("fr") -> 0.20d, Lang("ja") -> 0.20d)) === Lang("ja")
    }

    "detect short Hebrew" in {
      LangDetector.detectShortText("מצוין") === Lang("he")
    }
  }
}
