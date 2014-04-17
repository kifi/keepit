package com.keepit.common.crypto

import com.keepit.common.db.Id
import scala.util.Try

case class PublicIdConfiguration(key: String)

trait ModelWithPublicId[T] {

  def publicId(id: Long)(implicit config: PublicIdConfiguration): Try[String] = {
    (new TripleDES(config.key).encryptLongToStr(id, CipherConv.Base32Conv)).map {
      prefix._1.toString + prefix._2.toString + _
    }
  }

  val prefix: (Char, Char)
}

object ModelWithPublicId {

  def decode[T](publicId: String)(implicit config: PublicIdConfiguration): Try[Id[T]] = {
    (new TripleDES(config.key).decryptStrToLong(publicId.drop(2), CipherConv.Base32Conv)) map Id[T] _
  }
}
