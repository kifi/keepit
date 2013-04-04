package com.keepit.search

import com.keepit.common.logging.Logging
import org.apache.lucene.search.similarities.DefaultSimilarity

object Similarity extends Logging {
  trait NewTF extends DefaultSimilarity {
    override def tf(freq: Int) = 1.0f - (1.0f / (freq + 1.0f))
  }
  trait NoTF extends DefaultSimilarity {
    override def tf(freq: Int) = 1.0f
  }
  trait NoCoord extends DefaultSimilarity {
    override def coord(overlap: Int, maxOverlap: Int) = 1.0f
  }
  trait ProportionalCoord extends DefaultSimilarity{
    override def coord(overlap: Int, maxOverlap: Int) = (overlap.toFloat) / (maxOverlap.toFloat)
  }
  trait SquaredCoord extends DefaultSimilarity {
    override def coord(overlap: Int, maxOverlap: Int) = {
      val coord = (overlap.toFloat) / (maxOverlap.toFloat)
      coord * coord
    }
  }
  trait ReciprocalCoord extends DefaultSimilarity {
    override def coord(overlap: Int, maxOverlap: Int) = 1.0f / ((1 + maxOverlap - overlap).toFloat)
  }
  trait NoFieldNorm extends DefaultSimilarity {
    override def decodeNormValue(b: Byte) = 1.0f
  }

  private[this] val similarities: Map[String, DefaultSimilarity] = Map(
    ("default" -> new DefaultSimilarity with NewTF with NoCoord),
    ("propotionalCoord" -> new DefaultSimilarity with ProportionalCoord),
    ("squaredCoord" -> new DefaultSimilarity with SquaredCoord),
    ("reciprocalCoord" -> new DefaultSimilarity with ReciprocalCoord),
    ("noTF" -> new DefaultSimilarity with NoTF with NoCoord),
    ("noTF-propotionalCoord" -> new DefaultSimilarity with ProportionalCoord),
    ("noTF-squaredCoord" -> new DefaultSimilarity with NoTF with SquaredCoord),
    ("noTF-reciprocalCoord" -> new DefaultSimilarity with NoTF with ReciprocalCoord),
    ("noFieldNorm" -> new DefaultSimilarity with NoFieldNorm with NoCoord),
    ("noTF-noFieldNorm" -> new DefaultSimilarity with NoTF with NoFieldNorm with NoCoord)
  )

  def apply(name: String) = {
    def fallback = {
      log.warn("Similarity(%s) not found".format(name))
      similarities("default")
    }
    similarities.getOrElse(name, fallback)
  }
}
