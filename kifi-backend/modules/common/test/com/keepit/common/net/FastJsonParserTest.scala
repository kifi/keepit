package com.keepit.common.net

import com.keepit.common.strings.UTF8
import org.specs2.mutable.Specification

import play.api.libs.json._

class FastJsonParserTest extends Specification {
  "FastJsonParser" should {
    "do simple parsing" in {
      val json = FastJsonParser().parse("""{"foo": "bar"}""".getBytes(UTF8))
      json === Json.obj("foo" -> "bar")
      (json \ "foo").as[String] === "bar"
    }
    "deal with empty object" in {
      val json = FastJsonParser().parse("""{}""".getBytes(UTF8))
      json === Json.obj()
      json.toString === "{}"
    }
    "deal with empty array" in {
      val json = FastJsonParser().parse("""[]""".getBytes(UTF8))
      json === JsArray()
      json.toString === "[]"
    }
  }
}
