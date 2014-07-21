package com.keepit.common.crypto

import java.lang.Long.reverseBytes
import java.nio.ByteBuffer
import javax.crypto.{ Cipher, SecretKey, SecretKeyFactory }
import javax.crypto.spec.{ IvParameterSpec, PBEKeySpec, SecretKeySpec }

import org.apache.commons.codec.binary.Base64

private[crypto] class Aes64BitCipher(key: SecretKey, ivSpec: IvParameterSpec) {

  private val ecipher = Cipher.getInstance("AES/CFB8/NoPadding")
  private val dcipher = Cipher.getInstance("AES/CFB8/NoPadding")

  ecipher.init(Cipher.ENCRYPT_MODE, key, ivSpec)
  dcipher.init(Cipher.DECRYPT_MODE, key, ivSpec)

  def encrypt(value: Long): Long = crypt(value, ecipher)
  def decrypt(value: Long): Long = crypt(value, dcipher)

  private def crypt(value: Long, cipher: Cipher): Long = {
    val buffer = ByteBuffer.allocate(8)
    buffer.putLong(0, reverseBytes(value))
    val bytes: Array[Byte] = cipher.doFinal(buffer.array)
    buffer.rewind
    buffer.put(bytes)
    buffer.rewind
    reverseBytes(buffer.getLong)
  }

}

private object Aes64BitCipher {
  // added sources of randomness generated using SecureRandom
  val keySalt: Array[Byte] = Array(-112, 67, 64, 26, -122, -43, 55, -61)
  val keyIterationCount = 19

  def apply(passPhrase: String, ivSpec: IvParameterSpec): Aes64BitCipher = {
    val spec = new PBEKeySpec(passPhrase.toCharArray, keySalt, keyIterationCount, 128)
    val pbeKey = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec)
    val aesKey = new SecretKeySpec(pbeKey.getEncoded(), "AES")
    new Aes64BitCipher(aesKey, ivSpec)
  }
}
