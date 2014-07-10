package com.keepit.cortex.nlp

import edu.stanford.nlp.parser.lexparser.LexicalizedParser
import edu.stanford.nlp.ling.Label

object POSTagger {
  val enabled = true
  val parser = LexicalizedParser.loadModel()

  def tagOneWord(word: String): Label = {
    var tree = parser.parse(word)
    val depth = tree.depth()
    (0 until depth - 1).foreach { i => tree = tree.lastChild() }
    tree.label()
  }
}

