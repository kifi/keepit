package com.keepit.common.crypto

import java.security.{ Security, SecureRandom }
import org.apache.commons.codec.binary.{ Base32, Base64 }
import javax.crypto.Cipher
import javax.crypto.spec.{ IvParameterSpec, SecretKeySpec }
import java.nio.ByteBuffer
import org.bouncycastle.jce.provider.BouncyCastleProvider
import scala.util.{ Success, Failure, Try }

sealed abstract case class EncryptionScheme(name: String, cipherName: String, nonceLength: Int) {
  def checkValid(data: Array[Byte]): Boolean = true
}

object EncryptionScheme {
  // AES with custom integrity check (DANGER!! - but probably good enough for now.)
  object Custom extends EncryptionScheme("custom", "AES/CBC/PKCS5Padding", 16)
  // The real thing - authenticated encryption using AES_128_GCM
  object GCM extends EncryptionScheme("gcm", "AES/CBC/PKCS5Padding", 12) {
    override def checkValid(data: Array[Byte]): Boolean =
      data.slice(8, 16).filter(_ != 0).length == 0
  }
}

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

  def generateAES128key() = {
    toBase64(randomBytes(16))
  }

  Security.addProvider(new BouncyCastleProvider())

  def encryptLong(value: Long, key: String, encryptionScheme: EncryptionScheme): String = {
    val buffer = ByteBuffer.allocate(8)
    buffer.putLong(value)
    buffer.flip()
    val ivBytes = randomBytes(encryptionScheme.nonceLength)
    val ivSpec = new IvParameterSpec(ivBytes)
    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding", BouncyCastleProvider.PROVIDER_NAME)
    val secretKey = new SecretKeySpec(fromBase64(key), "AES")
    cipher.init(Cipher.ENCRYPT_MODE, secretKey, ivSpec)
    toBase32(Array.concat(ivBytes, cipher.doFinal(buffer.array())))
  }

  def decryptLong(ciphertext: String, key: String, encryptionScheme: EncryptionScheme): Try[Long] = {
    val bytes = fromBase32(ciphertext.toUpperCase)
    val ivBytes = bytes.slice(0, encryptionScheme.nonceLength)
    val ciphertextBytes = bytes.slice(encryptionScheme.nonceLength, bytes.length)
    val ivSpec = new IvParameterSpec(ivBytes)
    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding", BouncyCastleProvider.PROVIDER_NAME)
    val secretKey = new SecretKeySpec(fromBase64(key), "AES")
    cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)
    val decrypted = cipher.doFinal(ciphertextBytes)
    if (!encryptionScheme.checkValid(decrypted)) Failure(new Exception("Invalid ciphertext"))
    val buffer = ByteBuffer.allocate(8)
    buffer.put(decrypted)
    buffer.flip()
    Success(buffer.getLong)
  }
}
