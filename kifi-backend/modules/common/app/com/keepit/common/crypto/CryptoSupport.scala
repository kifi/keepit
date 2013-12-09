package com.keepit.common.crypto

import java.security.SecureRandom
import org.apache.commons.codec.binary.Base64

trait CryptoSupport {

  val base64 = new Base64(true)
  def toBase64(bytes: Array[Byte]): String = base64.encode(bytes).map(_.toChar).mkString
  def fromBase64(s: String): Array[Byte] = base64.decode(s.getBytes)

  def randomBytes(length: Int)(implicit random: SecureRandom) = {
    val out = Array.ofDim[Byte](length)
    random.nextBytes(out)
    out
  }
}
