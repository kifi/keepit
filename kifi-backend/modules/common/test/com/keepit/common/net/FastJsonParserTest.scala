package com.keepit.common.net

import com.keepit.common.strings.UTF8
import org.specs2.mutable.Specification

import play.api.libs.json._

class FastJsonParserTest extends Specification {
  "FastJsonParser" should {
    "do simple parsing" in {
      val (json, tracking) = new FastJsonParser().parse("""{"foo": "bar"}""".getBytes(UTF8), -1)
      json === Json.obj("foo" -> "bar")
      (json \ "foo").as[String] === "bar"
      tracking.get.message === "long message, no attempt to match"
    }
    "deal with empty object" in {
      val (json, tracking) = new FastJsonParser().parse("""{}""".getBytes(UTF8), -1)
      json === Json.obj()
      json.toString === "{}"
      tracking.get.message === "matching empty object bytes"
    }
    "deal with empty array" in {
      val (json, tracking) = new FastJsonParser().parse("""[]""".getBytes(UTF8), -1)
      json === JsArray()
      json.toString === "[]"
      tracking.get.message === "matching empty array bytes"
    }
    "deal with nulls" in {
      val nullStr = """null""".getBytes(UTF8)
      nullStr.size === 4
      val (json, tracking) = new FastJsonParser().parse(nullStr, -1)
      json === JsNull
      json.toString === "null"
      tracking.get.message === "matching null bytes"
    }
  }
}
