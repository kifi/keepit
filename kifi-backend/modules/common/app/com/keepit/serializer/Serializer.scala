package com.keepit.serializer

import play.api.libs.json._
import com.keepit.common.logging.Logging
import scala.Option

trait BinaryFormat[T] {
  def writes(value: Option[T]): Array[Byte] = {
    value match {
      case Some(obj) => writes(1.toByte, obj)
      case None => Array[Byte](0)
    }
  }
  def reads(bytes: Array[Byte]): Option[T] = {
    if (bytes(0) == 1.toByte) Some(reads(bytes, 1, bytes.length - 1))
    else if (bytes(0) == 0.toByte) None
    else Some(reads(bytes, 0, bytes.length))
  }

  protected def writes(prefix: Byte, value: T): Array[Byte]
  protected def reads(obj: Array[Byte], offset: Int, length: Int): T
}

object BinaryFormat {
  implicit val sliderHistoryBinaryFormat = SliderHistoryBinarySerializer.sliderHistoryBinarySerializer
}

object TraversableFormat {
  private def materialize[U, T <: Traversable[U]](implicit traversableFormatter: Format[T]) = traversableFormatter
  def seq[U](implicit formatter: Format[U]) = materialize[U, Seq[U]]
  def set[U](implicit formatter: Format[U]) = materialize[U, Set[U]]
}

trait Serializer[T] {
  def writes(value: Option[T]): Any
  def reads(obj: Any): Option[T]
}

trait LocalSerializer[T] { // for use with local/in-memory caching
  def localWrites(value: Option[T]): Any
  def localReads(obj: Any): Option[T]
}

case class SafeLocalSerializer[T](serializer: Serializer[T]) extends LocalSerializer[T] {
  def localWrites(value: Option[T]) = serializer.writes(value)
  def localReads(obj: Any) = serializer.reads(obj)
}

case class NoCopyLocalSerializer[T]() extends LocalSerializer[T] with Logging { // works with immutable objects
  def localWrites(value: Option[T]) = value
  def localReads(obj: Any): Option[T] = obj.asInstanceOf[Option[T]]
}

class SerializerException(msg: String) extends Exception(msg)

object Serializer {
  def apply[T](formatter: Format[T]): Serializer[T] = new Serializer[T] {
    def writes(value: Option[T]) = Json.stringify(value match {
      case Some(obj) => Json.obj(
        "data" -> Json.toJson[T](obj)(formatter),
        "has_data" -> JsBoolean(true)
      )
      case None => Json.obj("has_data" -> JsBoolean(false))
    })
    def reads(obj: Any) = {
      val rawJs = Json.parse(obj.asInstanceOf[String])
      val js = (rawJs \ "has_data") match {
        case JsBoolean(false) => None
        case JsBoolean(true) => Some(rawJs \ "data")
        case _ => Some(rawJs)
      }
      js.map(Json.fromJson[T](_)(formatter).get)
    }
  }

  def apply[P <: AnyVal] = new Serializer[P] {
    def writes(value: Option[P]): Any = value match {
      case Some(obj) => (true, obj)
      case None => (false, null)
    }
    def reads(obj: Any) =
      try {
        obj.asInstanceOf[(Boolean, P)] match {
          case (true, x) => Some(x)
          case (false, _) => None
        }
      } catch {
        case e: Throwable => Some(obj.asInstanceOf[P])
      }
  }

  def apply[T](binaryFormatter: BinaryFormat[T]): Serializer[T] = new Serializer[T] {
    def writes(value: Option[T]) = binaryFormatter.writes(value)
    def reads(obj: Any) = {
      val rawData = obj.asInstanceOf[Array[Byte]]
      binaryFormatter.reads(rawData)
    }
  }

  val string = new Serializer[String] {
    def writes(value: Option[String]): Any = value match {
      case Some(str) => "$" + str
      case None => "#"
    }
    def reads(obj: Any) = {
      val rawString = obj.asInstanceOf[String]
      if (rawString.length == 0) Some(rawString)
      else {
        if (rawString(0) == '$') Some(rawString.tail)
        else if (rawString(0) == '#') None
        else Some(rawString)
      }
    }
  }
}
