package com.keepit.cortex.nlp

import org.specs2.mutable.Specification

class POSTaggerTest extends Specification {
  "basic pos tagger" should {
    "work" in {
      POSTagger.tagOneWord("apple").value() === "NN"
        POSTagger.tagOneWord("apples").value() === "NNS"
      POSTagger.tagOneWord("speak").value() === "VB"
    }
  }
}
