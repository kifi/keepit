package com.keepit.common.crypto

import javax.crypto._
import javax.crypto.spec._
import java.security.spec._
import scala.util.Try
import org.apache.commons.codec.binary.{ Base64, Base32 }

/** DO NOT USE FOR REAL CRYPTO **/

sealed abstract class CipherConv {
  def encode(data: Array[Byte]): String
  def decode(str: String): Array[Byte]
}

object CipherConv {
  object Base64Conv extends CipherConv {
    override def encode(data: Array[Byte]): String = Base64.encodeBase64URLSafeString(data)
    override def decode(str: String): Array[Byte] = Base64.decodeBase64(str)
  }
  object Base32Conv extends CipherConv {
    override def encode(data: Array[Byte]): String = new Base32().encodeAsString(data).takeWhile(_ != '=').toLowerCase
    override def decode(str: String): Array[Byte] = new Base32().decode(str.toUpperCase)
  }
}

class TripleDES(passPhrase: String) {

  private val salt: Array[Byte] = Array(0xA9.asInstanceOf[Byte], 0x9B.asInstanceOf[Byte], 0xC8.asInstanceOf[Byte], 0x32.asInstanceOf[Byte], 0x56.asInstanceOf[Byte], 0x35.asInstanceOf[Byte], 0xE3.asInstanceOf[Byte], 0x03.asInstanceOf[Byte])
  private val iterationCount: Int = 19
  private val longSize = java.lang.Long.SIZE / 8

  private val paramSpec: AlgorithmParameterSpec = new PBEParameterSpec(salt, iterationCount)
  private val keySpec: KeySpec = new PBEKeySpec(passPhrase.toCharArray, salt, iterationCount)
  private val key: SecretKey = SecretKeyFactory.getInstance("PBEWithMD5AndDES").generateSecret(keySpec)
  private val ecipher: Cipher = Cipher.getInstance(key.getAlgorithm)
  private val dcipher: Cipher = Cipher.getInstance(key.getAlgorithm)

  ecipher.init(Cipher.ENCRYPT_MODE, key, paramSpec)
  dcipher.init(Cipher.DECRYPT_MODE, key, paramSpec)

  def encryptLongToStr(id: Long, conv: CipherConv = CipherConv.Base64Conv): Try[String] = Try {
    val buffer = java.nio.ByteBuffer.allocate(longSize)
    buffer.putLong(0, id)
    val array = buffer.array
    val enc: Array[Byte] = ecipher.doFinal(array)
    conv.encode(enc)
  }

  def decryptStrToLong(str: String, conv: CipherConv = CipherConv.Base64Conv): Try[Long] = Try {
    val buffer = java.nio.ByteBuffer.allocate(longSize)
    val dec: Array[Byte] = conv.decode(str)
    val out: Array[Byte] = dcipher.doFinal(dec)
    buffer.put(out)
    buffer.flip
    buffer.getLong
  }

  def encryptStr(str: String, conv: CipherConv = CipherConv.Base64Conv): Try[String] = Try {
    val utf8: Array[Byte] = str.getBytes("UTF8")
    val enc: Array[Byte] = ecipher.doFinal(utf8)
    conv.encode(enc)
  }

  def decryptStr(str: String, conv: CipherConv = CipherConv.Base64Conv): Try[String] = Try {
    val dec: Array[Byte] = conv.decode(str)
    val utf8: Array[Byte] = dcipher.doFinal(dec)
    new String(utf8, "UTF8")
  }
}
