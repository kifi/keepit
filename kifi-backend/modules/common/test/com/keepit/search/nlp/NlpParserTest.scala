package com.keepit.search.nlp

import org.specs2.mutable.Specification

class NlpParserTest extends Specification {
  "NlpParser" should {
    "gives tagged segments" in {
      NlpParser.enabled
      var t1 = System.currentTimeMillis()
      var sent = "Chinese restaurant in bay area"
      var tagged = NlpParser.getTaggedSegments(sent)
      tagged === List(("NP", "Chinese restaurant"), ("PP", "in bay area"))
      var t2 = System.currentTimeMillis()
      println(s"nlp parser time elapesed: ${t2 - t1}")

      t1 = System.currentTimeMillis()
      sent = "how to convert raw to jpeg"
      tagged = NlpParser.getTaggedSegments(sent)
      t2 = System.currentTimeMillis()

      println(s"nlp parser time elapesed: ${t2 - t1}")

      t1 = System.currentTimeMillis()
      sent = "yellowstone national park hotels"
      tagged = NlpParser.getTaggedSegments(sent)
      t2 = System.currentTimeMillis()
      tagged === List(("VP", "yellowstone national park hotels"))       // TODO: better segmentation (finer)
      println(s"nlp parser time elapesed: ${t2 - t1}")

      t1 = System.currentTimeMillis()
      sent = "machine learning and natrual language processing"
      tagged = NlpParser.getTaggedSegments(sent)
      t2 = System.currentTimeMillis()
      tagged === List(("NP", "machine learning"), ("NP", "natrual language processing"))
      println(s"nlp parser time elapesed: ${t2 - t1}")
    }
  }

  "remove overlapping segments" in {
    var segs = Array((0, 3), (1, 4), (4, 4), (2, 3), (3, 6), (4, 6), (2, 7), (8, 8))
    NlpParser.removeOverlapping(segs) === Seq((0, 3), (4, 6))
  }
}