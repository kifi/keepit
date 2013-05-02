package com.keepit.common.crypto;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

import javax.crypto.Cipher;
import javax.crypto.CipherOutputStream;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.apache.commons.codec.net.URLCodec;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.codec.digest.DigestUtils;


// Modified from UserVoice's dev example
public class UserVoiceTokenGenerator {
  private static final String USERVOICE_SUBDOMAIN = "kifi";
  private static final String SSO_KEY = "bdf86063a47a8fbc916ac250a2559bc6";
  private static final byte[] INIT_VECTOR = "OpenSSL for Ruby".getBytes();
  private SecretKeySpec secretKeySpec;
  private IvParameterSpec ivSpec;
  private URLCodec urlCodec = new URLCodec("ASCII");
  private Base64 base64 = new Base64();
  private static UserVoiceTokenGenerator INSTANCE = new UserVoiceTokenGenerator();

  public static UserVoiceTokenGenerator getInstance() {
    if (INSTANCE == null) {
      INSTANCE = new UserVoiceTokenGenerator();
    }
    return INSTANCE;
  }

  private UserVoiceTokenGenerator() {
    String salted = SSO_KEY + USERVOICE_SUBDOMAIN;
    byte[] hash = DigestUtils.sha(salted);
    byte[] saltedHash = new byte[16];
    System.arraycopy(hash, 0, saltedHash, 0, 16);

    secretKeySpec = new SecretKeySpec(saltedHash, "AES");
    ivSpec = new IvParameterSpec(INIT_VECTOR);
  }

  private void encrypt(InputStream in, OutputStream out) throws Exception {
    try {
      byte[] buf = new byte[1024];

      Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
      cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, ivSpec);

      out = new CipherOutputStream(out, cipher);

      int numRead = 0;
      while ((numRead = in.read(buf)) >= 0) {
        out.write(buf, 0, numRead);
      }
      out.close();
    } catch (InvalidKeyException e) {
      e.printStackTrace();
    } catch (NoSuchAlgorithmException e) {
      e.printStackTrace();
    } catch (NoSuchPaddingException e) {
      e.printStackTrace();
    } catch (InvalidAlgorithmParameterException e) {
      e.printStackTrace();
    } catch (java.io.IOException e) {
      e.printStackTrace();
    }
  }

  public String create(String json) throws Exception {
    byte[] data = json.getBytes();

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (int i = 0; i < 16; i++) {
      data[i] ^= INIT_VECTOR[i];
    }
    encrypt(new ByteArrayInputStream(data), out);

    String token = new String(urlCodec.encode(base64.encode(out.toByteArray())));
    return token;
  }

  public static String createSSOToken(String userId, String displayName, String email, String avatarUrl) {
    try {
      String json = "{\"guid\":\""+userId+"\",\"display_name\":\""+displayName+"\",\"email\":\""+email+"\",\"avatar_url\":\""+avatarUrl+"\"}";
      //String json = "{\"trusted\":true,\"guid\":\""+"1"+"\",\"display_name\":\""+"KiFi support"+"\",\"email\":\""+"uservoice@42go.com"+"\",\"avatar_url\":\""+"https://www.kifi.com/assets/images/logo2.png"+"\"}";

      return UserVoiceTokenGenerator.getInstance().create(json);
    } catch (Exception e) {
      e.printStackTrace();
      return "";
    }
  }
}
