package com.keepit.common.db

import play.api.libs.json._
import play.api.mvc.{PathBindable, QueryStringBindable}

case class Id[T](id: Long) {
  override def toString = id.toString
}

object Id {
  def format[T]: Format[Id[T]] =
    Format(__.read[Long].map(Id(_)), new Writes[Id[T]]{ def writes(o: Id[T]) = JsNumber(o.id) })

  implicit def queryStringBinder[T](implicit longBinder: QueryStringBindable[Long]) = new QueryStringBindable[Id[T]] {
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
