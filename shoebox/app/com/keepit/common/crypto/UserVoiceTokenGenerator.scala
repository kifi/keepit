package com.keepit.common.crypto

import org.apache.commons.codec.net.URLCodec
import org.apache.commons.codec.binary.Base64
import javax.crypto.spec.SecretKeySpec
import javax.crypto.spec.IvParameterSpec
import org.apache.commons.codec.digest.DigestUtils
import java.io.InputStream
import java.io.OutputStream
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import com.keepit.common.logging.Logging
import com.google.inject.Singleton

case class UserVoiceSSOToken(value: String)

trait UserVoiceTokenGenerator {
  def createSSOToken(userId: String, displayName: String, email: String, avatarUrl: String): UserVoiceSSOToken
}

@Singleton
class UserVoiceTokenGeneratorImpl extends UserVoiceTokenGenerator with Logging {
  private final val USERVOICE_SUBDOMAIN = "kifi"
  private final val SSO_KEY = "bdf86063a47a8fbc916ac250a2559bc6"
  private final val INIT_VECTOR = "OpenSSL for Ruby".getBytes()
  private val hash = DigestUtils.sha(SSO_KEY + USERVOICE_SUBDOMAIN).toSeq
  
  private val secretKeySpec = new SecretKeySpec(hash.take(16).toArray, "AES")
  private val ivSpec = new IvParameterSpec(INIT_VECTOR)
  private val urlCodec = new URLCodec("ASCII")
  private val base64 = new Base64()
  
  private def encrypt(in: InputStream): ByteArrayOutputStream = {
    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
    cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, ivSpec)
    val buf = new Array[Byte](1024)
    
    val byteStream = new ByteArrayOutputStream()
    val out = new CipherOutputStream(byteStream, cipher)
    try {
      Iterator.continually(in.read(buf)).takeWhile(_ >= 0) foreach { numRead =>
        out.write(buf, 0, numRead)
      }
    } catch {
      case ex: Throwable =>
        // integrate into your logging
        ex.printStackTrace()
    } finally {
      out.close()
    }
    byteStream
  }
  
  def create(json: String): String = {
    val jsonBytes = json.getBytes() 
    val data = INIT_VECTOR.zip(jsonBytes).take(16).map({ case (i, d) => (i ^ d).byteValue }) ++ jsonBytes.drop(INIT_VECTOR.length)
    val out = encrypt(new ByteArrayInputStream(data));

    new String(urlCodec.encode(base64.encode(out.toByteArray)))
  }
  
  def createSSOToken(userId: String, displayName: String, email: String, avatarUrl: String): UserVoiceSSOToken = {
    try {
      val json = "{\"guid\":\""+userId+"\",\"display_name\":\""+displayName+"\",\"email\":\""+email+"\",\"avatar_url\":\""+avatarUrl+"\"}"
      UserVoiceSSOToken(create(json))
    } catch {
      case ex: Throwable =>
        ex.printStackTrace()
        UserVoiceSSOToken("")
    }
  }
}