package com.keepit.common.crypto

import com.keepit.common.db.Id

case class PublicIdConfiguration(key: String)

trait ModelWithPublicId[T] {

  def publicId(id: Long)(implicit config: PublicIdConfiguration): String = {
    CryptoSupport.encryptLong(id, config.key)
  }
}

object ModelWithPublicId {

  def decode[T](publicId: String)(implicit config: PublicIdConfiguration): Option[Id[T]] = {
    try {
      CryptoSupport.decryptLong(publicId, config.key) map Id[T] _
    } catch {
      case e: Exception => None
    }
  }
}
