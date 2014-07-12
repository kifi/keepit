package com.keepit.macros

import com.keepit.heimdal.{ DelightedAnswerSource, DelightedAnswerSources, BasicDelightedAnswer }
import com.keepit.model.Word2VecKeywords
import org.specs2.mutable.Specification
import play.api.libs.json._

class JsonFormatAnnotationTest extends Specification {

  "@json annotation" should {

    "create correct formatter for DelightedAnswerSource" in {
      val obj1 = DelightedAnswerSources.ANDROID
      val json1 = Json.toJson(obj1)
      json1 === JsString("android")
      Json.fromJson[DelightedAnswerSource](json1) === JsSuccess(obj1)
    }

    "formatter for DelightedAnswerSource should be used for the BasicDelightedAnswer formatter" in {
      val obj2 = BasicDelightedAnswer(4, Some("Because I said so"), DelightedAnswerSources.IOS)
      val json2 = Json.toJson(obj2)
      json2 === Json.obj(
        "score" -> 4,
        "comment" -> "Because I said so",
        "source" -> "ios"
      )
      Json.fromJson[BasicDelightedAnswer](json2) === JsSuccess(obj2)
    }

    "create correct formatter for Word2VecKeywords" in {
      val obj = Word2VecKeywords(Seq("abc", "def"), Seq("asd", "fgh"), 12345)
      val json = Json.toJson(obj)
      json === Json.obj(
        "cosine" -> Seq("abc", "def"),
        "freq" -> Seq("asd", "fgh"),
        "wordCounts" -> 12345
      )
      Json.fromJson[Word2VecKeywords](json) === JsSuccess(obj)
    }
  }

}
