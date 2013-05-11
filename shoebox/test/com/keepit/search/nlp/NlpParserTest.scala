package com.keepit.search.nlp

import org.specs2.mutable.Specification

class NlpParserTest extends Specification {
  "NlpParser" should {
    "gives tagged segments" in {
      var sent = "Chinese restaurant in bay area"
      var tagged = NlpParser.getTaggedSegments(sent)
      tagged === List(("NP", "Chinese restaurant"), ("PP", "in bay area"))
    }
  }
}