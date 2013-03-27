package com.keepit.search

import com.keepit.common.logging.Logging
import org.apache.lucene.search.similarities.DefaultSimilarity

object Similarity extends Logging {
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

  private[this] val similarities = {
    var m = Map.empty[String, DefaultSimilarity]
    m += ("default" -> new DefaultSimilarity with NoCoord)
    m += ("propotionalCoord" -> new DefaultSimilarity with ProportionalCoord)
    m += ("squaredCoord" -> new DefaultSimilarity with SquaredCoord)
    m += ("reciprocalCoord" -> new DefaultSimilarity with ReciprocalCoord)
    m += ("noTF" -> new DefaultSimilarity with NoTF with NoCoord)
    m += ("noTF-propotionalCoord" -> new DefaultSimilarity with ProportionalCoord)
    m += ("noTF-squaredCoord" -> new DefaultSimilarity with NoTF with SquaredCoord)
    m += ("noTF-reciprocalCoord" -> new DefaultSimilarity with NoTF with ReciprocalCoord)
    m
  }

  def apply(name: String) = {
    def fallback = {
      log.warn("Similarity(%s) not found".format(name))
      similarities("default")
    }
    similarities.getOrElse(name, fallback)
  }
}
