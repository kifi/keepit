package com.keepit.search.spellcheck

import org.apache.commons.codec.language.Metaphone
import org.apache.lucene.search.spell.{ LevensteinDistance, NGramDistance, StringDistance }

class MetaphoneDistance {
  val mp = new Metaphone()
  val lev = new LevensteinDistance()
  val ngram = new NGramDistance()
  def getDistance(a: String, b: String, smoothFactor: Float = 0.1f): Float = lev.getDistance(mp.metaphone(a), mp.metaphone(b)) max smoothFactor
  def getMultipliedDistance(a: String, b: String): Float = ngram.getDistance(a, b) * getDistance(a, b)
}

class CompositeDistance extends StringDistance {
  val mdist = new MetaphoneDistance()
  def getDistance(a: String, b: String): Float = mdist.getMultipliedDistance(a, b)
}
