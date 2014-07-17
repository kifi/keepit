package com.keepit.common.crypto

import org.specs2.mutable._

class Aes64BitCipherTest extends Specification {
  "Aes64BitCipher" should {
    "encrypt and decrypt" in {
      val cipher = Aes64BitCipher("test passphrase")
      val long = new java.util.Random().nextLong()
      val bytes = cipher.encrypt(long)
      bytes.length === 8
      bytes.count(_ == 0) must be_<(4)
      cipher.decrypt(bytes) === long
    }
  }
}
