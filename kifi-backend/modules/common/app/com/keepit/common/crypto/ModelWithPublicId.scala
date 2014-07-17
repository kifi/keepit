package com.keepit.common.crypto

import com.keepit.common.db.Id

import play.api.libs.json._
import play.api.mvc.{ PathBindable, QueryStringBindable }

import org.apache.commons.codec.binary.Base64

import scala.util.{ Failure, Success, Try }

case class PublicIdConfiguration(key: String) {
  lazy val aes64bit = Aes64BitCipher(key)
}

case class PublicId[T <: ModelWithPublicId[T]](id: String)

object PublicId {
  implicit def format[T <: ModelWithPublicId[T]]: Format[PublicId[T]] = Format(
    __.read[String].map(PublicId(_)),
    new Writes[PublicId[T]] { def writes(o: PublicId[T]) = JsString(o.id) }
  )

  implicit def queryStringBinder[T <: ModelWithPublicId[T]](implicit stringBinder: QueryStringBindable[String]) = new QueryStringBindable[PublicId[T]] {
    override def bind(key: String, params: Map[String, Seq[String]]): Option[Either[String, PublicId[T]]] = {
      stringBinder.bind(key, params) map {
        case Right(id) => Right(PublicId(id))
        case _ => Left("Unable to bind an PublicId")
      }
    }
    override def unbind(key: String, id: PublicId[T]): String = {
      stringBinder.unbind(key, id.id)
    }
  }

  implicit def pathBinder[T <: ModelWithPublicId[T]] = new PathBindable[PublicId[T]] {
    override def bind(key: String, value: String): Either[String, PublicId[T]] =
      Right(PublicId(value)) // TODO: handle errors if value is malformed

    override def unbind(key: String, id: PublicId[T]): String = id.toString
  }
}

// TODO: Cipher must be a singleton, not re-created for every encode/decode.

trait ModelWithPublicId[T <: ModelWithPublicId[T]] {

  val prefix: String
  val id: Option[Id[T]]

  def publicId(implicit config: PublicIdConfiguration): Try[PublicId[T]] = {
    id.map { someId =>
      Try(PublicId[T](prefix + Base64.encodeBase64URLSafeString(config.aes64bit.encrypt(someId.id))))
    }.getOrElse(Failure(new IllegalStateException("model has no id")))
  }

}

trait ModelWithPublicIdCompanion[T <: ModelWithPublicId[T]] {

  val prefix: String

  def decode(publicId: String)(implicit config: PublicIdConfiguration): Try[Id[T]] = {
    val reg = raw"^$prefix(.*)$$".r
    Try {
      reg.findFirstMatchIn(publicId).map(_.group(1)).map { identifier =>
        Id[T](config.aes64bit.decrypt(Base64.decodeBase64(identifier)))
      }.get
    }
  }
}
