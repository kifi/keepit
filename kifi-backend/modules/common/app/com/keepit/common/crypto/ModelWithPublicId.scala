package com.keepit.common.crypto

import javax.crypto.spec.IvParameterSpec
import com.keepit.common.db.Id
import play.api.libs.json._
import play.api.mvc.{ PathBindable, QueryStringBindable }

import scala.collection.concurrent.TrieMap
import scala.util.{ Success, Failure, Try }

case class PublicIdConfiguration(key: String) {
  private val cache = TrieMap.empty[IvParameterSpec, Aes64BitCipher]
  def aes64bit(iv: IvParameterSpec) = cache.getOrElseUpdate(iv, Aes64BitCipher(iv, key))
}

case class PublicId[T <: ModelWithPublicId[T]](val id: String)

object PublicId {
  implicit def format[T <: ModelWithPublicId[T]]: Format[PublicId[T]] = Format(
    __.read[String].map(PublicId[T]),
    new Writes[PublicId[T]] { def writes(o: PublicId[T]) = JsString(o.id) }
  )

  implicit def queryStringBinder[T <: ModelWithPublicId[T]](implicit stringBinder: QueryStringBindable[String]) = new QueryStringBindable[PublicId[T]] {
    override def bind(key: String, params: Map[String, Seq[String]]): Option[Either[String, PublicId[T]]] = {
      stringBinder.bind(key, params) map {
        case Right(id) => Right(PublicId(id))
        case _ => Left("Not a valid Public Id")
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
  val id: Option[Id[T]]
}

trait ModelWithPublicIdCompanion[T <: ModelWithPublicId[T]] {

  val prefix: String
  /* Can be generated with:
    val sr = new java.security.SecureRandom()
    val arr = new Array[Byte](16)
    sr.nextBytes(arr)
    arr
  */
  protected[this] val prefixIvSpec: IvParameterSpec

  def publicId(publicId: PublicId[T])(implicit config: PublicIdConfiguration): Try[Id[T]] = {
    if (publicId.id.startsWith(prefix)) {
      Try(Id[T](config.aes64bit(prefixIvSpec).decrypt(Base62Long.decode(publicId.id.substring(prefix.length))))).flatMap { id =>
        // IDs must be less than 100 billion. This gives us "plenty" of room, while catching nearly* all invalid IDs.
        if (id.id > 0 || id.id > 100000000000L) {
          Success(id)
        } else {
          Failure(new IllegalArgumentException(s"Expected $publicId to be in a valid range: ${id.id}"))
        }
      }
    } else {
      Failure(new IllegalArgumentException(s"Expected $publicId to start with $prefix"))
    }
  }

  def publicId(id: Id[T])(implicit config: PublicIdConfiguration): Try[PublicId[T]] = {
    Try(PublicId[T](prefix + Base62Long.encode(config.aes64bit(prefixIvSpec).encrypt(id.id))))
  }
}
