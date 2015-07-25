package com.keepit.rover.document.utils

import org.specs2.mutable._

import scala.util.Random

class SignatureTest extends Specification {

  "Signature" should {

    "convert to/from base64" in {
      val arr = new Array[Byte](100)
      val rnd = new Random
      (0 until 100).foreach(i => arr(i) = (rnd.nextInt.toByte))

      val sig = Signature(arr)
      val base64 = sig.toBase64
      val decoded = Signature(base64)

      decoded.equals(sig) === true
    }

    "handle the empty base64" in {
      (Signature("") similarTo Signature(new Array[Byte](100))) === 0.0d
    }
  }
}
