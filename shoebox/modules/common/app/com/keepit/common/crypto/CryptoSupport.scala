package com.keepit.common.crypto

import java.security.SecureRandom
import org.apache.commons.codec.binary.Base64

trait CryptoSupport {

  def toBase64(bytes: Array[Byte]): String = Base64.encodeBase64(bytes).map(_.toChar).mkString
  def fromBase64(s: String): Array[Byte] = Base64.decodeBase64(s.getBytes)

  def randomBytes(length: Int)(implicit random: SecureRandom) = {
    val out = Array.ofDim[Byte](length)
    random.nextBytes(out)
    out
  }
}
