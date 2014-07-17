package com.keepit.common.crypto

import java.nio.ByteBuffer
import javax.crypto.{ Cipher, SecretKey, SecretKeyFactory }
import javax.crypto.spec.{ IvParameterSpec, PBEKeySpec, SecretKeySpec }

private[crypto] class Aes64BitCipher(key: SecretKey) {

  private val ecipher = Cipher.getInstance("AES/CFB64/NoPadding")
  private val dcipher = Cipher.getInstance("AES/CFB64/NoPadding")

  ecipher.init(Cipher.ENCRYPT_MODE, key, Aes64BitCipher.iv)
  dcipher.init(Cipher.DECRYPT_MODE, key, Aes64BitCipher.iv)

  def encrypt(value: Long): Array[Byte] = {
    val buffer = ByteBuffer.allocate(8)
    buffer.putLong(0, value)
    ecipher.doFinal(buffer.array)
  }

  def decrypt(value: Array[Byte]): Long = {
    if (value.length == 8) {
      val bytes: Array[Byte] = dcipher.doFinal(value)
      val buffer = ByteBuffer.allocate(8).put(bytes)
      buffer.flip
      buffer.getLong
    } else {
      throw new IllegalArgumentException(s"wrong size: ${value.length}")
    }
  }

}

private object Aes64BitCipher {
  // added sources of randomness generated using SecureRandom
  val keySalt: Array[Byte] = Array(-112, 67, 64, 26, -122, -43, 55, -61)
  val keyIterationCount = 19
  val iv = new IvParameterSpec(Array(-72, -49, 51, -61, 42, 43, 123, -61, 64, 122, -121, -55, 117, -51, 12, 21))

  def apply(passPhrase: String): Aes64BitCipher = {
    val spec = new PBEKeySpec(passPhrase.toCharArray, keySalt, keyIterationCount, 128)
    val pbeKey = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec)
    val aesKey = new SecretKeySpec(pbeKey.getEncoded(), "AES")
    new Aes64BitCipher(aesKey)
  }
}
