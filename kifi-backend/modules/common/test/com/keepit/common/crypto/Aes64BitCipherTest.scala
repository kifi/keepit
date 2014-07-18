package com.keepit.common.crypto

import org.specs2.mutable._

class Aes64BitCipherTest extends Specification {
  "Aes64BitCipher" should {
    "encrypt and decrypt" in {
      val cipher = Aes64BitCipher("test passphrase")
      val plaintext = new java.util.Random().nextLong()
      val encrypted = cipher.encrypt(plaintext)
      encrypted !== plaintext
      cipher.decrypt(encrypted) === plaintext
    }
  }
}
