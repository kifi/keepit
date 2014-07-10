package com.keepit.common.crypto

import javax.crypto.{ SecretKey, Cipher, SecretKeyFactory }
import javax.crypto.spec.{ IvParameterSpec, DESKeySpec }
import scala.util.Try

class RatherInsecureDESCrypt {
  private val ivBytes = Array[Byte](0x68, 0x65, 0x6c, 0x70, 0x20, 0x73, 0x74, 0x75)
  private val ivSpec = new IvParameterSpec(ivBytes)

  def stringToKey(keyStr: String) = {
    SecretKeyFactory.getInstance("DES").generateSecret(new DESKeySpec(keyStr.getBytes))
  }

  def crypt(key: SecretKey, in: String): String = {
    val input = in.getBytes
    val cipher = Cipher.getInstance("DES/CBC/PKCS5Padding")
    cipher.init(Cipher.ENCRYPT_MODE, key, ivSpec)
    val encrypted = new Array[Byte](cipher.getOutputSize(input.length))
    var enc_len = cipher.update(input, 0, input.length, encrypted, 0)
    cipher.doFinal(encrypted, enc_len)

    val encoded = CryptoSupport.toBase64(encrypted)
    if (encoded.endsWith("=")) {
      encoded.takeWhile(_ != '=')
    } else {
      encoded
    }
  }

  def decrypt(key: SecretKey, encrypted: String): Try[String] = Try {
    val input = CryptoSupport.fromBase64(encrypted)
    val cipher = Cipher.getInstance("DES/CBC/PKCS5Padding")
    cipher.init(Cipher.DECRYPT_MODE, key, ivSpec)
    val decrypted = new Array[Byte](cipher.getOutputSize(input.length))
    var dec_len = cipher.update(input, 0, input.length, decrypted, 0)
    cipher.doFinal(decrypted, dec_len)
    decrypted.filter(_ != 0).map(_.asInstanceOf[Char]).mkString
  }
}
