package com.keepit.common.crypto

import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.apache.commons.codec.binary.Base64
import com.keepit.common.strings._

object PBKDF2 extends CryptoSupport {

  val ITERATION_COUNT = 10000
  val HASH_SIZE = 32 // in bytes
  val SALT_SIZE = 8 // in bytes

  val HMAC_ALGORITHM = "HmacSHA256"

  /**
   * Compute a hash of the specified password, using the PBKDF2 algorithm
   * as defined in RFC 2898 (http://www.ietf.org/rfc/rfc2898.txt).
   * Returns the Base64 encoding of the concatenated random salt and key.
   */
  def hash(password: String)(implicit random: SecureRandom): String = {
    val salt = randomBytes(SALT_SIZE)
    val key = pbkdf2(password, salt, ITERATION_COUNT, HASH_SIZE, HMAC_ALGORITHM)
    toBase64(salt ++ key)
  }

  /**
   * Check if the given password matches a previously-computed password hash.
   */
  def check(password: String, hash: String): Boolean = {
    val (salt, key) = fromBase64(hash).splitAt(SALT_SIZE)
    val passwordKey = pbkdf2(password, salt, ITERATION_COUNT, HASH_SIZE, HMAC_ALGORITHM)
    (passwordKey.toSeq == key.toSeq)
  }

  /**
   * Compute the PBKDF2 key for the given password, salt, iteration count and key length,
   * using the specified HMAC algorithm for the hashing steps.
   */
  def pbkdf2(password: Array[Byte], salt: Array[Byte], count: Int, keyLen: Int, algorithm: String): Array[Byte] = {
    val hmac = Mac.getInstance(algorithm)
    val keySpec = new SecretKeySpec(password, algorithm)
    hmac.init(keySpec)

    var bytes = Array.empty[Byte]
    var block = 1
    while (bytes.length < keyLen) {
      bytes ++= hashBlock(hmac, password, salt, count, block)
      block += 1
    }
    bytes.take(keyLen)
  }

  private def hashBlock(hmac: Mac, password: Array[Byte], salt: Array[Byte], count: Int, block: Int): Array[Byte] = {
    val blockBytes = Array((block >>> 24).toByte, (block >>> 16).toByte, (block >>> 8).toByte, block.toByte)

    var temp = hmac.doFinal(salt ++ blockBytes)
    var hash = temp

    var i = 0
    while (i < count - 1) {
      temp = hmac.doFinal(temp)
      var j = 0
      while (j < hash.length) {
        hash(j) = (hash(j) ^ temp(j)).toByte
        j += 1
      }
      i += 1
    }
    hash
  }

}
