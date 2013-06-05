package com.keepit.serializer

import play.api.libs.json._
import com.keepit.model.SocialUserInfo
import com.keepit.model.SocialUserInfo
import play.api.libs.json.JsArray
import com.keepit.model.SocialUserInfo
import play.api.libs.json.JsObject

class SequenceFormat[T](implicit formatter: Format[T]) extends Format[Seq[T]] {
  def writes(values: Seq[T]) = JsArray(values.map(formatter.writes))
  def reads(obj: JsValue) = JsSuccess(obj.as[Seq[T]])
}

object SequenceFormat {
  def apply[T](implicit formatter: Format[T]) = new SequenceFormat[T]
}

trait BinaryFormat[T] {
  def writes(value: T): Array[Byte]
  def reads(obj: Array[Byte]): T
}

object BinaryFormat {
  implicit val clickHistoryBinaryFormat = ClickHistoryBinarySerializer.clickHistoryBinarySerializer
  implicit val sliderHistoryBinaryFormat = SliderHistoryBinarySerializer.sliderHistoryBinarySerializer
  implicit val browsingHistoryBinaryFormat = BrowsingHistoryBinarySerializer.browsingHistoryBinarySerializer
}

trait Serializer[T] {
  def writes(value: T): Any
  def reads(obj: Any): T
}

object Serializer {
  def apply[T](formatter: Format[T]): Serializer[T] =  new Serializer[T] {
    def writes(value: T) = formatter.writes(value)
    def reads(obj: Any) = formatter.reads(Json.parse(obj.asInstanceOf[String])).get
  }

  def apply[T <: AnyVal] = new Serializer[T] {
    def writes(value: T): Any = value
    def reads(obj: Any) = obj.asInstanceOf[T]
  }

  def apply[T](binaryFormatter: BinaryFormat[T]): Serializer[T] = new Serializer[T] {
    def writes(value: T) = binaryFormatter.writes(value)
    def reads(obj: Any) = binaryFormatter.reads(obj.asInstanceOf[Array[Byte]])
  }

}