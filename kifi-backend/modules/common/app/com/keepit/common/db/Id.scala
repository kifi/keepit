package com.keepit.common.db

import play.api.libs.json._
import play.api.mvc.{ PathBindable, QueryStringBindable }
import org.msgpack.ScalaMessagePack
import com.keepit.model.serialize.{ MsgPackOptIdTemplate, MsgPackIdTemplate }

case class Id[T](id: Long) { //use "extends AnyVal" at Scala 2.11.0 https://issues.scala-lang.org/browse/SI-6260
  override def toString = id.toString
}

object Id {

  ScalaMessagePack.messagePack.register(classOf[Id[Any]], new MsgPackIdTemplate[Any]())
  ScalaMessagePack.messagePack.register(classOf[Option[Id[Any]]], new MsgPackOptIdTemplate[Any]())

  implicit def format[T]: Format[Id[T]] =
    Format(__.read[Long].map(Id(_)), new Writes[Id[T]] { def writes(o: Id[T]) = JsNumber(o.id) })

  implicit def queryStringBinder[T](implicit longBinder: QueryStringBindable[Long]): QueryStringBindable[Id[T]] = new QueryStringBindable[Id[T]] {
    override def bind(key: String, params: Map[String, Seq[String]]): Option[Either[String, Id[T]]] = {
      longBinder.bind(key, params) map {
        case Right(id) => Right(Id(id))
        case _ => Left("Unable to bind an Id")
      }
    }
    override def unbind(key: String, id: Id[T]): String = {
      longBinder.unbind(key, id.id)
    }
  }

  implicit def pathBinder[T](implicit longBinder: PathBindable[Long]) = new PathBindable[Id[T]] {
    override def bind(key: String, value: String): Either[String, Id[T]] =
      longBinder.bind(key, value) match {
        case Right(id) => Right(Id(id))
        case _ => Left("Unable to bind an Id")
      }
    override def unbind(key: String, id: Id[T]): String = id.id.toString
  }
}
