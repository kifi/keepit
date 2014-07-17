package com.keepit.common.crypto

import com.keepit.common.db.Id
import com.google.common.io.BaseEncoding

import play.api.libs.json._
import play.api.mvc.{ PathBindable, QueryStringBindable }

import scala.util.{ Failure, Success, Try }

case class PublicIdConfiguration(key: String)

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

  private[crypto] val coder = BaseEncoding.base32().lowerCase().omitPadding()
}

// TODO: Cipher must be a singleton, not re-created for every encode/decode.

trait ModelWithPublicId[T <: ModelWithPublicId[T]] {

  val prefix: String
  val id: Option[Id[T]]

  def publicId(implicit config: PublicIdConfiguration): Try[PublicId[T]] = {
    id.map { someId =>
      Success(PublicId[T](prefix + PublicId.coder.encode(Aes64BitCipher(config.key).encrypt(someId.id))))
    }.getOrElse(Failure(new IllegalStateException("model has no id")))
  }

}

trait ModelWithPublicIdCompanion[T <: ModelWithPublicId[T]] {

  val prefix: String

  def decode(publicId: String)(implicit config: PublicIdConfiguration): Try[Id[T]] = {
    if (publicId.startsWith(prefix)) {
      Try(Id[T](Aes64BitCipher(config.key).decrypt(PublicId.coder.decode(publicId.substring(prefix.length)))))
    } else {
      Failure(new IllegalArgumentException(s"Expected $publicId to start with $prefix"))
    }
  }

}
