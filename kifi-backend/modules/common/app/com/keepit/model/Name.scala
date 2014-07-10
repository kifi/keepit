package com.keepit.model

import play.api.libs.json._
import play.api.mvc.{ PathBindable, QueryStringBindable }

case class Name[T](name: String) {
  override def toString = name
}

object Name {
  def format[T]: Format[Name[T]] =
    Format(__.read[String].map(Name(_)), new Writes[Name[T]] { def writes(o: Name[T]) = JsString(o.name) })

  implicit def queryStringBinder[T](implicit stringBinder: QueryStringBindable[String]) = new QueryStringBindable[Name[T]] {
    override def bind(key: String, params: Map[String, Seq[String]]): Option[Either[String, Name[T]]] = {
      stringBinder.bind(key, params) map {
        case Right(name) => Right(Name(name))
        case _ => Left("Unable to bind a Name")
      }
    }
    override def unbind(key: String, name: Name[T]): String = {
      stringBinder.unbind(key, name.name)
    }
  }

  implicit def pathBinder[T](implicit stringBinder: PathBindable[String]) = new PathBindable[Name[T]] {
    override def bind(key: String, value: String): Either[String, Name[T]] =
      stringBinder.bind(key, value) match {
        case Right(name) => Right(Name(name))
        case _ => Left("Unable to bind a Name")
      }
    override def unbind(key: String, name: Name[T]): String = name.name.toString
  }
}