package com.keepit.common.crypto

import com.keepit.common.db.Id
import scala.util.Try

case class PublicIdConfiguration(key: String)

trait ModelWithPublicId[T] {

  def publicId(id: Long)(implicit config: PublicIdConfiguration): String = {
    (new TripleDES(config.key).encryptLongToStr(id)).get
  }
}

object ModelWithPublicId {

  def decode[T](publicId: String)(implicit config: PublicIdConfiguration): Try[Id[T]] = {
    (new TripleDES(config.key).decryptStrToLong(publicId)) map Id[T] _
  }
}
