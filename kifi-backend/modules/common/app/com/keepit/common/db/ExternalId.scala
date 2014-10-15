package com.keepit.common.db

import java.util.UUID
import play.api.libs.json._
import play.api.mvc.{ PathBindable, QueryStringBindable }

case class ExternalId[T](id: String) {
  ExternalId.verifyIdFormat(id)
  override def toString = id
}

object ExternalId {

  def verifyIdFormat(id: String): Unit = {
    if (!ExternalId.UUIDPattern.pattern.matcher(id).matches()) {
      throw new Exception(s"""external id "$id" does not match uuid pattern""")
    }
  }

  implicit def format[T]: Format[ExternalId[T]] = Format(
    __.read[String].map(ExternalId(_)),
    new Writes[ExternalId[T]] { def writes(o: ExternalId[T]) = JsString(o.id) }
  )

  val UUIDPattern = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}".r

  def asOpt[T](id: String): Option[ExternalId[T]] = {
    if (ExternalId.UUIDPattern.pattern.matcher(id).matches())
      Some(ExternalId[T](id))
    else None
  }

  implicit def queryStringBinder[T](implicit stringBinder: QueryStringBindable[String]) = new QueryStringBindable[ExternalId[T]] {
    override def bind(key: String, params: Map[String, Seq[String]]): Option[Either[String, ExternalId[T]]] = {
      stringBinder.bind(key, params) map {
        case Right(id) =>
          ExternalId.asOpt[T](id) match {
            case Some(extId) => Right(extId)
            case None => Left(s"Unable to bind an ExternalId with $id")
          }
        case _ => Left("Unable to bind an ExternalId")
      }
    }
    override def unbind(key: String, id: ExternalId[T]): String = {
      stringBinder.unbind(key, id.id)
    }
  }

  implicit def pathBinder[T] = new PathBindable[ExternalId[T]] {
    override def bind(key: String, value: String): Either[String, ExternalId[T]] = {
      ExternalId.asOpt[T](value) match {
        case Some(extId) => Right(extId)
        case None => Left(s"Unable to bind to ExternalID with $value")
      }
    }

    override def unbind(key: String, id: ExternalId[T]): String = id.toString
  }

  def apply[T](): ExternalId[T] = ExternalId(UUID.randomUUID.toString)
}

trait SurrogateExternalId {
  val id: String
  ExternalId.verifyIdFormat(id)
  override def toString = id
}

abstract class SurrogateExternalIdCompanion[T <: SurrogateExternalId] {

  def apply(id: String): T

  implicit def format: Format[T] = Format(
    __.read[String].map(apply),
    new Writes[T] { def writes(o: T) = JsString(o.id) }
  )

  def asOpt(id: String): Option[T] = {
    if (ExternalId.UUIDPattern.pattern.matcher(id).matches())
      Some(apply(id))
    else None
  }

  implicit def queryStringBinder(implicit stringBinder: QueryStringBindable[String]) = new QueryStringBindable[T] {
    override def bind(key: String, params: Map[String, Seq[String]]): Option[Either[String, T]] = {
      stringBinder.bind(key, params) map {
        case Right(id) =>
          asOpt(id) match {
            case Some(extId) => Right(extId)
            case None => Left(s"Unable to bind a SurrogateExternalId with $id")
          }
        case _ => Left("Unable to bind a SurrogateExternalId")
      }
    }
    override def unbind(key: String, id: T): String = {
      stringBinder.unbind(key, id.id)
    }
  }

  implicit def pathBinder = new PathBindable[T] {
    override def bind(key: String, value: String): Either[String, T] = {
      asOpt(value) match {
        case Some(extId) => Right(extId)
        case None => Left(s"Unable to bind to SurrogateExternalId with $value")
      }
    }

    override def unbind(key: String, id: T): String = id.toString
  }
}

