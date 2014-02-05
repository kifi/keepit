package com.keepit.common

import org.apache.commons.lang3.StringUtils
import play.api.libs.json.Json.JsValueWrapper
import play.api.libs.json.{Json, JsObject, JsNull, JsString, JsUndefined}

package object strings {
  val UTF8 = "UTF-8"
  implicit def fromByteArray(bytes: Array[Byte]): String = if (bytes == null) "" else new String(bytes, UTF8)
  implicit def toByteArray(str: String): Array[Byte] = str.getBytes(UTF8)
  def fromByteArray(bytes: Array[Byte], offset: Int, length: Int): String = if (bytes == null) "" else new String(bytes, offset, length, UTF8)

  implicit class AbbreviateString(str: String) {
    def abbreviate(count: Int): String = StringUtils.abbreviate(str, count)
  }

  private val humanFriendlyCharacters = Array('a','b','c','d','e','f','g','h','j','k','m','n','p','q','r','s','t','w','x','y','z','2','3','4','5','6','7','8','9')
  def humanFriendlyToken(length: Int) = {
    def nextChar: Char = {
      val rnd = util.Random.nextInt(humanFriendlyCharacters.length)
      humanFriendlyCharacters(rnd)
    }
    Seq.fill(length)(nextChar).mkString
  }

  implicit class StringWithNoLineBreaks(str: String) {
    def trimAndRemoveLineBreaks(): String = str.replaceAll("""[\t\n\x0B\f\r]""", " ").replaceAll("""[ ]{2,}""", " ").trim()
  }


  implicit class OptionWrappedMembersJsObject(obj: Seq[(String, Option[JsValueWrapper])]) {
    def stripOptions(): JsObject = {
      Json.obj(obj.map { v =>
        if (v._2.nonEmpty) Some(v._1 -> v._2.get) else None
      }.flatten: _*)
    }
  }
  implicit class OptionWrappedJsObject(obj: JsObject) {
    def stripJsNulls(): JsObject = {
      JsObject(obj.value.map { v =>
        v._2 match {
          case null => None
          case s: JsUndefined => None
          case JsNull => None
          case JsString(null) => None
          case JsString("null") => None
          case other => Some(v._1 -> v._2)
        }
      }.flatten.toSeq)
    }
  }

}
