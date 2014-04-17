package com.keepit.common.crypto

import java.security.{Security, SecureRandom}
import org.apache.commons.codec.binary.{Base32, Base64}
import javax.crypto.Cipher
import javax.crypto.spec.{IvParameterSpec, SecretKeySpec}
import java.nio.ByteBuffer
import org.bouncycastle.jce.provider.BouncyCastleProvider

object CryptoSupport {

  val base64 = new Base64(true)
  val base32 = new Base32(true)
  def toBase64(bytes: Array[Byte]): String = base64.encode(bytes).map(_.toChar).mkString
  def fromBase64(s: String): Array[Byte] = base64.decode(s.getBytes)
  def toBase32(bytes: Array[Byte]): String = base32.encode(bytes).map(_.toChar).mkString
  def fromBase32(s: String): Array[Byte] = base32.decode(s.getBytes)

  val random = new SecureRandom()
  def randomBytes(length: Int) = {
    val out = Array.ofDim[Byte](length)
    random.nextBytes(out)
    out
  }

  // Encryption/Decryption using AES_128_GCM

  Security.addProvider(new BouncyCastleProvider())

  def generateAES128key() = {
    toBase64(randomBytes(16))
  }

  val NONCE_LENGTH = 12

  def encryptLong(value: Long, key: String): String = {
    val buffer = ByteBuffer.allocate(8)
    buffer.putLong(value)
    buffer.flip()
    val ivBytes = randomBytes(NONCE_LENGTH)
    val ivSpec = new IvParameterSpec(ivBytes)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding", BouncyCastleProvider.PROVIDER_NAME)
    val secretKey = new SecretKeySpec(fromBase64(key), "AES")
    cipher.init(Cipher.ENCRYPT_MODE, secretKey, ivSpec)
    toBase32(Array.concat(ivBytes,cipher.doFinal(buffer.array())))
  }

  def decryptLong(ciphertext: String, key: String): Option[Long] = {
    try {
      val bytes = fromBase32(ciphertext.toUpperCase)
      val ivBytes = bytes.slice(0,NONCE_LENGTH)
      val ciphertextBytes = bytes.slice(NONCE_LENGTH,bytes.length)
      val ivSpec = new IvParameterSpec(ivBytes)
      val cipher = Cipher.getInstance("AES/GCM/NoPadding", BouncyCastleProvider.PROVIDER_NAME)
      val secretKey = new SecretKeySpec(fromBase64(key), "AES")
      cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)
      val decrypted = cipher.doFinal(ciphertextBytes)
      val buffer = ByteBuffer.allocate(8)
      buffer.put(decrypted)
      buffer.flip()
      Some(buffer.getLong)
    } catch {
      case e: Exception => None
    }
  }
}
