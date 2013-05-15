package com.keepit.common.crypto

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.apache.commons.codec.binary.Base64

import com.keepit.common.strings._

case class AESKey(bytes: Array[Byte]) {
  override def toString = AES.toBase64(bytes)
}

object AESKey {
  def apply(keyStr: String): AESKey = AESKey(AES.fromBase64(keyStr))

  def apply(): AESKey = AES.generateKey()
}

object AES extends CryptoSupport {

  val ALGORITHM = "AES"
  val TRANSFORMATION = "AES/CTR/NoPadding"
  val IV_LENGTH = 16 // length in bytes of initialization vector for CTR mode

  val HMAC_ALGORITHM = "HmacSHA256"
  val HMAC_LENGTH = 32 // length in bytes of HMAC hash

  /**
   * Generate a key suitable for use with AES. Valid key sizes are 128, 192 or 256.
   */
  def generateKey(size: Int = 256): AESKey = {
    val keyGen = KeyGenerator.getInstance(ALGORITHM)
    keyGen.init(size)
    val keyBytes = keyGen.generateKey().getEncoded()
    AESKey(keyBytes)
  }

  /**
   * Encrypt a message with AES and the given AES key.
   * Uses AES in CTR mode with a random initialization vector (iv)
   * generated from the given SecureRandom.  The concatenated iv and
   * ciphertext are hashed using HMAC_SHA256, and the final result
   * is the Base64 encoding of the concatenated mac, iv, and ciphertext.
   */
  def encrypt(message: String, key: AESKey)(implicit random: SecureRandom): String = {
    val keySpec = new SecretKeySpec(key.bytes, ALGORITHM)

    val iv = randomBytes(IV_LENGTH)
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, keySpec, new IvParameterSpec(iv))
    val cipherBytes = cipher.doFinal(message.getBytes(ENCODING))

    val encrypted = iv ++ cipherBytes
    val mac = hmac(key.bytes, encrypted)
    toBase64(mac ++ encrypted)
  }

  /**
   * Decrypt a message with AES and the given Base64-encoded key.
   * The message must be the Base64-encoded concatenation of mac, iv,
   * and ciphertext, as produced by encrypt.
   */
  def decrypt(message: String, key: AESKey): String = {
    val keySpec = new SecretKeySpec(key.bytes, ALGORITHM)

    val (mac, encrypted) = fromBase64(message).splitAt(HMAC_LENGTH)
    val (iv, cipherBytes) = encrypted.splitAt(IV_LENGTH)

    if (mac.toSeq != hmac(key.bytes, encrypted).toSeq) {
      throw new Exception("MAC failed")
    }

    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.DECRYPT_MODE, keySpec, new IvParameterSpec(iv))
    cipher.doFinal(cipherBytes)
  }

  private def hmac(key: Array[Byte], message: Array[Byte]): Array[Byte] = {
    val hmac = Mac.getInstance(HMAC_ALGORITHM)
    val keySpec = new SecretKeySpec(key, HMAC_ALGORITHM)
    hmac.init(keySpec)
    hmac.doFinal(message)
  }
}

