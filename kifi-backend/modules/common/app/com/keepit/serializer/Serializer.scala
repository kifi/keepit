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
  def writes(value: Option[T]): Any
  def reads(obj: Any): Option[T]
}

class SerializerException(msg: String) extends Exception(msg)

object Serializer {
  def apply[T](formatter: Format[T]): Serializer[T] =  new Serializer[T] {
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
          case (false,_) => None
        }
      }
      catch {
        case e: Throwable => Some(obj.asInstanceOf[P])
      }
  }

  def apply[T](binaryFormatter: BinaryFormat[T]): Serializer[T] = new Serializer[T] {
    def writes(value: Option[T]) = value match{
      case Some(obj) => 1.toByte +: binaryFormatter.writes(obj)
      case None => Array[Byte](0)
    }
    def reads(obj: Any) = {
      val rawData = obj.asInstanceOf[Array[Byte]]
      if (rawData(0)==1.toByte) Some(binaryFormatter.reads(rawData.tail))
      else if (rawData(0)==0.toByte) None
      else Some(binaryFormatter.reads(rawData))
    }
  }

  val string = new Serializer[String] {
    def writes(value: Option[String]): Any = value match {
      case Some(str) => "$" + str
      case None => "#"
    }
    def reads(obj: Any) = {
      val rawString =  obj.asInstanceOf[String]
      if (rawString.length==0) Some(rawString)
      else {
        if (rawString(0)=='$') Some(rawString.tail) 
        else if (rawString(0)=='#') None
        else Some(rawString)
      }
    }
  }
}
