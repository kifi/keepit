package com.keepit.common.crypto

import com.keepit.common.db.Id
import play.api.libs.json._
import play.api.mvc.{ PathBindable, QueryStringBindable }
import scala.util.{ Failure, Try }

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
}

trait ModelWithPublicId[T <: ModelWithPublicId[T]] {

  val prefix: String
  val id: Option[Id[T]]

  def publicId(implicit config: PublicIdConfiguration): Try[String] = {
    id.map { someId =>
      new TripleDES(config.key).encryptLongToStr(someId.id, CipherConv.Base64Conv).map {
        prefix + _
      }
    }.getOrElse(Failure(new IllegalStateException("No id exists")))
  }

}

trait ModelWithPublicIdCompanion[T <: ModelWithPublicId[T]] {

  val prefix: String

  def decode(publicId: String)(implicit config: PublicIdConfiguration): Try[Id[T]] = {
    val reg = raw"^$prefix(.*)$$".r
    Try {
      reg.findFirstMatchIn(publicId).map(_.group(1)).map { identifier =>
        new TripleDES(config.key).decryptStrToLong(identifier, CipherConv.Base64Conv).map(Id[T]).toOption
      }.flatten.get
    }
  }
}
