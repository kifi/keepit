package com.keepit.search

import org.junit.runner.RunWith
import org.specs2.mutable._
import org.specs2.runner.JUnitRunner
import play.api.Play.current
import play.api.libs.json.Json
import play.api.test._
import play.api.test.Helpers._
import scala.math._
import scala.util.Random

@RunWith(classOf[JUnitRunner])
class IdFilterCompressorTest extends SpecificationWithJUnit {

  "IdFilterCompressor" should {
    "comrpess/decompress id set" in {
      val rand = new Random
      val idSet = (0 to 100).foldLeft(Set.empty[Long]){ (s, n) => s + rand.nextInt(1000).toLong }
      
      val bytes = IdFilterCompressor.toByteArray(idSet)
      val decoded = IdFilterCompressor.toSet(bytes)
      
      //println(bytes.mkString(","))
      decoded === idSet
    }
    
    "encode/decode id set" in {
      val rand = new Random
      val idSet = (0 to 100).foldLeft(Set.empty[Long]){ (s, n) => s + rand.nextInt(1000).toLong }
      
      val base64 = IdFilterCompressor.fromSetToBase64(idSet)
      val decoded = IdFilterCompressor.fromBase64ToSet(base64)
      
      //println(base64)
      decoded === idSet
    }
    
    "handle the empty string" in {
      val decoded = IdFilterCompressor.fromBase64ToSet("")
      
      decoded === Set.empty[Long]
    }
    
  }
}
