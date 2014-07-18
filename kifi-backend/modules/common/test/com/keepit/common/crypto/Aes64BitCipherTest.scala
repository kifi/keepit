package com.keepit.common.crypto

import javax.crypto.spec.IvParameterSpec

import org.specs2.mutable._

class Aes64BitCipherTest extends Specification {
  "Aes64BitCipher" should {
    "encrypt and decrypt" in {
      val ivSpec = new IvParameterSpec(Array(-71, -49, 51, -61, 42, 41, 123, -61, 64, 122, -121, -55, 117, -51, 12, 21))

      val cipher = Aes64BitCipher("test passphrase", ivSpec)
      val plaintext = new java.util.Random().nextLong()
      val encrypted = cipher.encrypt(plaintext)
      encrypted !== plaintext
      cipher.decrypt(encrypted) === plaintext
    }
  }
}
