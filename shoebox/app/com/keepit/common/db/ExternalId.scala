package com.keepit.common.db

import play.api.mvc.{PathBindable, QueryStringBindable}
import java.util.UUID

case class ExternalId[T](id: String) {
  if (!ExternalId.UUID_PATTERN.pattern.matcher(id).matches()) {
    throw new Exception("external id [%s] does not match uuid pattern".format(id))
  }
  override def toString = id
}

object ExternalId {

  val UUID_PATTERN = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$".r

  implicit def queryStringBinder[T](implicit stringBinder: QueryStringBindable[String]) = new QueryStringBindable[ExternalId[T]] {
    override def bind(key: String, params: Map[String, Seq[String]]): Option[Either[String, ExternalId[T]]] = {
      stringBinder.bind(key, params) map {
        case Right(id) => Right(ExternalId(id))
        case _ => Left("Unable to bind an ExternalId")
      }
    }
    override def unbind(key: String, id: ExternalId[T]): String = {
      stringBinder.unbind(key, id.id)
    }
  }

  implicit def pathBinder[T] = new PathBindable[ExternalId[T]] {
    override def bind(key: String, value: String): Either[String, ExternalId[T]] =
      Right(ExternalId(value)) // TODO: handle errors if value is malformed

    override def unbind(key: String, id: ExternalId[T]): String = id.toString
  }

  def apply[T](): ExternalId[T] = ExternalId(UUID.randomUUID.toString)
}

