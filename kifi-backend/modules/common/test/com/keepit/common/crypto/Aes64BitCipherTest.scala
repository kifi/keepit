package com.keepit.common.crypto

import java.nio.charset.StandardCharsets.ISO_8859_1
import javax.crypto.spec.IvParameterSpec

import org.specs2.mutable._

class Aes64BitCipherTest extends Specification {
  val cipher = Aes64BitCipher("L0i5 L4n3", new IvParameterSpec("Jared = Superman".getBytes(ISO_8859_1)))

  "Aes64BitCipher" should {
    "encrypt and decrypt" in {
      val plaintext = new java.util.Random().nextLong()
      val encrypted = cipher.encrypt(plaintext)
      encrypted !== plaintext
      cipher.decrypt(encrypted) === plaintext
    }
    "permute bits well" in {  // FAIL!!!  TODO: fix
      cipher.encrypt(1) === 3943421451219986269L
      cipher.encrypt(2) === 3943421451219986270L
      cipher.encrypt(3) === 3943421451219986271L
      cipher.encrypt(4) === 3943421451219986264L
      cipher.encrypt(5) === 3943421451219986265L
      cipher.encrypt(6) === 3943421451219986266L
      cipher.encrypt(7) === 3943421451219986267L
      cipher.encrypt(8) === 3943421451219986260L
      cipher.encrypt(9) === 3943421451219986261L
    }
  }
}
