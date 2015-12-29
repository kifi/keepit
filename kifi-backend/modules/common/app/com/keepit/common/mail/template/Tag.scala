package com.keepit.common.mail.template

import play.api.libs.json._
import play.twirl.api.Html

import scala.util.matching.Regex

object Tag {
  val tagLeftDelim: String = "<%kf% "
  val tagRightDelim: String = " %kf%>"
  val tagRegex = (Regex.quoteReplacement(tagLeftDelim) + "(.*?)" + Regex.quoteReplacement(tagRightDelim)).r
}

trait Tag {
  def label: TagLabel

  def args: Seq[JsValue]

  def value: String = Tag.tagLeftDelim + Json.stringify(JsArray(Seq(JsString(label.value)) ++ args)) + Tag.tagRightDelim

  def toHtml: Html = Html(value)

  override def toString() = value
}

case class Tag0(label: TagLabel) extends Tag {
  def args = Seq.empty
}

case class Tag1[S](label: TagLabel, arg: S)(implicit val writer: Writes[S]) extends Tag {
  def args = Seq(writer.writes(arg))
}

case class Tag2[S, T](label: TagLabel, arg0: S, arg1: T)(implicit val writerS: Writes[S], writerT: Writes[T]) extends Tag {
  def args = Seq(writerS.writes(arg0), writerT.writes(arg1))
}

case class Tag3[S, T, U](label: TagLabel, arg0: S, arg1: T, arg2: U)(implicit val writerS: Writes[S], writerT: Writes[T], writerU: Writes[U]) extends Tag {
  def args = Seq(writerS.writes(arg0), writerT.writes(arg1), writerU.writes(arg2))
}

object TagLabel {
  implicit val format = new Format[TagLabel] {
    def reads(js: JsValue) = JsSuccess(TagLabel(js.as[JsString].value))

    def writes(o: TagLabel) = JsString(o.value)
  }
}

case class TagLabel(value: String) extends AnyVal

case class TagWrapper(label: TagLabel, args: Seq[JsValue])

object TagWrapper {
  implicit val reads = new Reads[TagWrapper] {
    def reads(jsVal: JsValue) = {
      val jsValues = jsVal.as[JsArray].value
      val label = jsValues.head.as[TagLabel]
      val tagArgs = jsValues.tail
      JsSuccess(TagWrapper(label, tagArgs))
    }
  }
}
