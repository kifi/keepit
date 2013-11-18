package com.keepit.search.spellcheck

import org.apache.commons.codec.language.Metaphone
import org.apache.lucene.search.spell.LevensteinDistance
import org.apache.lucene.search.spell.StringDistance

class MetaphoneDistance {
  val mp = new Metaphone()
  val lev = new  LevensteinDistance()
  def getDistance(a: String, b: String): Float = lev.getDistance(mp.metaphone(a), mp.metaphone(b))
  def getMultipliedDistance(a: String, b: String): Float = lev.getDistance(a, b) * getDistance(a, b)
}

class CompositeDistance extends StringDistance {
  val mdist = new MetaphoneDistance()
  def getDistance(a: String, b: String): Float = mdist.getMultipliedDistance(a, b)
}
