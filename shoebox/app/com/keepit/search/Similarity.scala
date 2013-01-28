package com.keepit.search

import com.keepit.common.logging.Logging
import org.apache.lucene.search.{Similarity => LuceneSimilarity}
import org.apache.lucene.search.DefaultSimilarity

object Similarity extends Logging {
  trait NoTF { this: LuceneSimilarity =>
    override def tf(freq: Int) = 1.0f
  }
  trait NoCoord { this: LuceneSimilarity =>
    override def coord(overlap: Int, maxOverlap: Int) = 1.0f
  }
  trait ProportionalCoord { this: LuceneSimilarity =>
    override def coord(overlap: Int, maxOverlap: Int) = (overlap.toFloat) / (maxOverlap.toFloat)
  }
  trait SquaredCoord { this: LuceneSimilarity =>
    override def coord(overlap: Int, maxOverlap: Int) = {
      val coord = (overlap.toFloat) / (maxOverlap.toFloat)
      coord * coord
    }
  }
  trait ReciprocalCoord { this: LuceneSimilarity =>
    override def coord(overlap: Int, maxOverlap: Int) = 1.0f / ((1 + maxOverlap - overlap).toFloat)
  }

  private[this] val similarities = {
    var m = Map.empty[String, LuceneSimilarity]
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