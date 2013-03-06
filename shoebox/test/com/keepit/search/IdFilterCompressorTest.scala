package com.keepit.search

import org.specs2.mutable._
import play.api.Play.current
import play.api.libs.json.Json
import play.api.test._
import play.api.test.Helpers._
import scala.math._
import scala.util.Random

class IdFilterCompressorTest extends Specification {
  val rand = new Random
  val idSet = (0 to 100).foldLeft(Set.empty[Long]){ (s, n) => s + rand.nextInt(1000).toLong }

  "IdFilterCompressor" should {
    "comrpess/decompress id set" in {
      val bytes = IdFilterCompressor.toByteArray(idSet)
      val decoded = IdFilterCompressor.toSet(bytes)

      decoded.size === idSet.size
      decoded.diff(idSet).isEmpty === true
      idSet.diff(decoded).isEmpty === true
    }

    "encode/decode id set" in {
      val base64 = IdFilterCompressor.fromSetToBase64(idSet)
      val decoded = IdFilterCompressor.fromBase64ToSet(base64)

      decoded.size === idSet.size
      decoded.diff(idSet).isEmpty === true
      idSet.diff(decoded).isEmpty === true
    }

    "handle the empty string" in {
      val decoded = IdFilterCompressor.fromBase64ToSet("")

      decoded.isEmpty === true
    }

    "detect truncated data" in {
      val base64 = IdFilterCompressor.fromSetToBase64(idSet)
      val truncated = base64.substring(0, base64.length - 1)
      IdFilterCompressor.fromBase64ToSet(truncated) must throwA[IdFilterCompressorException]
    }

    "detect corrupted data" in {
      val base64 = IdFilterCompressor.fromSetToBase64(idSet)
      val aChar = base64.charAt(base64.length/2)
      val corrupted = base64.replace(aChar, if(aChar == 'A') 'a' else 'A')
      IdFilterCompressor.fromBase64ToSet(corrupted) must throwA[IdFilterCompressorException]
    }
  }
}
