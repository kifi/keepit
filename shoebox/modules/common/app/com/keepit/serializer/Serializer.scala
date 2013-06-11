package com.keepit.serializer

import play.api.libs.json._

trait BinaryFormat[T] {
  def writes(value: T): Array[Byte]
  def reads(obj: Array[Byte]): T
}

object BinaryFormat {
  implicit val clickHistoryBinaryFormat = ClickHistoryBinarySerializer.clickHistoryBinarySerializer
  implicit val sliderHistoryBinaryFormat = SliderHistoryBinarySerializer.sliderHistoryBinarySerializer
  implicit val browsingHistoryBinaryFormat = BrowsingHistoryBinarySerializer.browsingHistoryBinarySerializer
}

object TraversableFormat {
  private def materialize[U, T <: Traversable[U]](implicit traversableFormatter: Format[T]) = traversableFormatter
  def seq[U](implicit formatter: Format[U]) = materialize[U, Seq[U]]
  def set[U](implicit formatter: Format[U]) = materialize[U, Set[U]]
}

trait Serializer[T] {
  def writes(value: T): Any
  def reads(obj: Any): T
}

object Serializer {
  def apply[T](formatter: Format[T]): Serializer[T] =  new Serializer[T] {
    def writes(value: T) = Json.toJson[T](value)(formatter)
    def reads(obj: Any) = Json.fromJson[T](Json.parse(obj.asInstanceOf[String]))(formatter).get
  }

  def apply[P <: AnyVal] = new Serializer[P] {
    def writes(value: P): Any = value
    def reads(obj: Any) = obj.asInstanceOf[P]
  }

  def apply[T](binaryFormatter: BinaryFormat[T]): Serializer[T] = new Serializer[T] {
    def writes(value: T) = binaryFormatter.writes(value)
    def reads(obj: Any) = binaryFormatter.reads(obj.asInstanceOf[Array[Byte]])
  }

  val string = new Serializer[String] {
    def writes(value: String): Any = value
    def reads(obj: Any) = obj.asInstanceOf[String]
  }
}
