package com.keepit.common.crypto

import com.keepit.common.db.Id
import scala.util.Try
import com.keepit.eliza.model._

case class PublicIdConfiguration(key: String)

trait ModelWithPublicId[T] {

  def publicId(id: Long)(implicit config: PublicIdConfiguration, obj: ModelWithPublicId[T]): Try[String] = {
    (new TripleDES(config.key).encryptLongToStr(id, CipherConv.Base32Conv)).map {
      obj.prefix + _
    }
  }

  val prefix: String = ""
}

object ModelWithPublicId {

  def decode[T](publicId: String)(implicit config: PublicIdConfiguration, obj: ModelWithPublicId[T]): Try[Id[T]] = {
    val reg = raw"^${obj.prefix}(.*)$$".r
    Try{reg.findFirstMatchIn(publicId).map(_.group(1)).map { identifier =>
      (new TripleDES(config.key).decryptStrToLong(identifier, CipherConv.Base32Conv)) map Id[T] _ toOption
    }.flatten.get}
  }
}
