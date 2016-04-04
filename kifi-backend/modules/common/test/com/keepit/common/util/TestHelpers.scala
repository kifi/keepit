package com.keepit.common.util

import org.specs2.matcher._
import play.api.libs.json._

object TestHelpers {
  def matchJson(small: JsValue) = new SmartJsonMatcher(small)
  class SmartJsonMatcher(expected: JsValue) extends Matcher[JsValue] {
    def apply[T <: JsValue](s: Expectable[T]) = {
      val diffs = deepCompare(s.value, expected)
      result(
        diffs.isEmpty,
        s.description + " matches exactly",
        diffs.map { case (path, diff) => s"mismatch at $path: $diff" }.mkString("\n"),
        s
      )
    }
  }


  private type JsonKind = String
  private def jsonKind(v: JsValue): JsonKind = v match {
    case _: JsObject => "JsObject"
    case arr: JsArray => s"JsArray#${arr.value.length}"
    case _: JsString => "JsString"
    case _: JsNumber => "JsNumber"
    case _: JsBoolean => "JsBoolean"
    case _: JsUndefined => "JsUndefined"
    case JsNull => "JsNull"
  }

  private type JsonPath = String
  private type JsonDiff = String
  private def deepCompare(a: JsValue, b: JsValue, path: String = "obj"): Seq[(JsonPath, JsonDiff)] = {
    (a, b) match {
      case _ if jsonKind(a) != jsonKind(b) =>
        Seq(path -> s"wrong types, ${jsonKind(a)} vs ${jsonKind(b)}")
      case (aObj: JsObject, bObj: JsObject) =>
        (aObj.keys ++ bObj.keys).toSeq.flatMap(k => deepCompare(aObj \ k, bObj \ k, s"$path.$k"))
      case (JsArray(as), JsArray(bs)) =>
        (as zip bs).zipWithIndex.flatMap { case ((av, bv), i) => deepCompare(av, bv, s"$path[$i]") }
      case _ =>
        if (a != b) Seq(path -> s"$a vs $b") else Seq.empty
    }
  }
}

