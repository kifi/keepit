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
    "permute bits well in consecutive small positive long values" in {
      cipher.encrypt(1) === -2644796545160457673L
      cipher.encrypt(2) === 5711187287630018612L
      cipher.encrypt(3) === -7392062738091901643L
      cipher.encrypt(4) === 8792213188438217266L
      cipher.encrypt(5) === 1380560063487963187L
      cipher.encrypt(6) === 5823553674093653552L
      cipher.encrypt(7) === -1056275590325946063L
      cipher.encrypt(8) === -1380969989776329666L
      cipher.encrypt(9) === 6145921475325096767L
      cipher.encrypt(9991) === 1164322715469585969L
      cipher.encrypt(9992) === 7677984277210665790L
      cipher.encrypt(9993) === -6461694352345770945L
      cipher.encrypt(9994) === -4690800597685002692L
      cipher.encrypt(9995) === -6676086683958697155L
      cipher.encrypt(9996) === 2845373186209553722L
      cipher.encrypt(9997) === 2539424297649662523L
      cipher.encrypt(9998) === 5757596379705742136L
      cipher.encrypt(9999) === 1086822315686484281L
    }
  }
}
