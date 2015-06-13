package com.keepit.common.crypto

import javax.crypto.spec.IvParameterSpec
import com.keepit.common.db.Id
import play.api.libs.json._
import play.api.mvc.{ PathBindable, QueryStringBindable }

import scala.collection.concurrent.TrieMap
import scala.util.{ Success, Failure, Try }

case class PublicIdConfiguration(key: String) {
  private val cache = TrieMap.empty[IvParameterSpec, Aes64BitCipher]
  def aes64bit(iv: IvParameterSpec) = cache.getOrElseUpdate(iv, {
    Aes64BitCipher(key, iv)
  })
}

case class PublicId[T <: ModelWithPublicId[T]](id: String)

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

    override def unbind(key: String, id: PublicId[T]): String = id.id
  }
}

trait ModelWithPublicId[T <: ModelWithPublicId[T]] {
  val id: Option[Id[T]]
}

object PublicIdRegistry {
  abstract class PubIdAccessor {
    def toPubId(idPL: Long)(implicit config: PublicIdConfiguration): String
    def toId(pubIdStr: String)(implicit config: PublicIdConfiguration): Long
  }
  private val _registry = scala.collection.concurrent.TrieMap.empty[String, PubIdAccessor]
  def register[T <: ModelWithPublicId[T]](c: ModelWithPublicIdCompanion[T]) = {
    val accessor = new PubIdAccessor {
      def toPubId(idL: Long)(implicit config: PublicIdConfiguration) = c.publicId(Id[T](idL)).id
      def toId(pubIdStr: String)(implicit config: PublicIdConfiguration) = c.decodePublicId(PublicId[T](pubIdStr)).get.id
    }
    _registry.putIfAbsent(c.getClass.getName.dropRight(1), accessor)
  }

  def registry: List[(String, PubIdAccessor)] = _registry.toList
}

trait ModelWithPublicIdCompanion[T <: ModelWithPublicId[T]] {

  PublicIdRegistry.register(this)

  protected[this] val publicIdPrefix: String
  /* Can be generated with:
    val sr = new java.security.SecureRandom()
    val arr = new Array[Byte](16)
    sr.nextBytes(arr)
    arr
  */
  protected[this] val publicIdIvSpec: IvParameterSpec

  def decodePublicId(publicId: PublicId[T])(implicit config: PublicIdConfiguration): Try[Id[T]] = {
    if (publicId.id.startsWith(publicIdPrefix)) {
      Try(config.aes64bit(publicIdIvSpec).decrypt(Base62Long.decode(publicId.id.substring(publicIdPrefix.length)))).flatMap { id =>
        // IDs must be less than 100 billion. This gives us "plenty" of room, while catching nearly* all invalid IDs.
        if (id > 0 && id < 100000000000L) {
          Success(Id[T](id))
        } else {
          Failure(new IllegalArgumentException(s"Expected $publicId to be in a valid range: $id"))
        }
      }
    } else {
      Failure(new IllegalArgumentException(s"Expected $publicId to start with $publicIdPrefix"))
    }
  }

  def publicId(id: Id[T])(implicit config: PublicIdConfiguration): PublicId[T] = {
    PublicId[T](publicIdPrefix + Base62Long.encode(config.aes64bit(publicIdIvSpec).encrypt(id.id)))
  }
}
