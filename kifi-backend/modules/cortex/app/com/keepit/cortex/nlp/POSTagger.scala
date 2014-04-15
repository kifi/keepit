package com.keepit.cortex.nlp

import edu.stanford.nlp.parser.lexparser.LexicalizedParser


object POSTagger {
  val enabled = true
  val parser = LexicalizedParser.loadModel()

  def parse(text: String) = {
    parser.parse(text)
  }
}
