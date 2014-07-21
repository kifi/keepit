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
    "permute bits well in consecutive long values" in {
      cipher.encrypt(Long.MinValue + 0) === -3204341755763582410L
      cipher.encrypt(Long.MinValue + 1) === 6578575491694318135L
      cipher.encrypt(Long.MinValue + 2) === -3512184749224757196L
      cipher.encrypt(Long.MinValue + 3) === 1831309298762874165L
      cipher.encrypt(Long.MinValue + 4) === -431158848416558542L
      cipher.encrypt(Long.MinValue + 5) === -7842811973366812621L
      cipher.encrypt(-6) === 8448278138216908492L
      cipher.encrypt(-5) === -3399511143732059443L
      cipher.encrypt(-4) === 8022967020688154058L
      cipher.encrypt(-3) === -345045693717200693L
      cipher.encrypt(-2) === 645273154061642952L
      cipher.encrypt(-1) === -7312720107387970871L
      cipher.encrypt(0) === 6019030281091193398L
      cipher.encrypt(1) === -2644796545160457673L
      cipher.encrypt(2) === 5711187287630018612L
      cipher.encrypt(3) === -7392062738091901643L
      cipher.encrypt(4) === 8792213188438217266L
      cipher.encrypt(5) === 1380560063487963187L
      cipher.encrypt(Long.MaxValue - 5) === -775093898637867316L
      cipher.encrypt(Long.MaxValue - 4) === 5823860893122716365L
      cipher.encrypt(Long.MaxValue - 3) === -1200405016166621750L
      cipher.encrypt(Long.MaxValue - 2) === 8878326343137575115L
      cipher.encrypt(Long.MaxValue - 1) === -8578098882793132856L
      cipher.encrypt(Long.MaxValue - 0) === 1910651929466804937L
    }
  }
}
