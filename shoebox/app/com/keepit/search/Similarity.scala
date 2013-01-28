package com.keepit.search

import com.keepit.common.logging.Logging
import org.apache.lucene.search.{Similarity => LuceneSimilarity}
import org.apache.lucene.search.DefaultSimilarity

object Similarity extends Logging {
  trait noTF { this: LuceneSimilarity =>
    override def tf(freq: Int) = 1.0f
  }
  trait noCoord { this: LuceneSimilarity =>
    override def coord(overlap: Int, maxOverlap: Int) = 1.0f
  }
  trait proportionalCoord { this: LuceneSimilarity =>
    override def coord(overlap: Int, maxOverlap: Int) = (overlap.toFloat) / (maxOverlap.toFloat)
  }
  trait squaredCoord { this: LuceneSimilarity =>
    override def coord(overlap: Int, maxOverlap: Int) = {
      val coord = (overlap.toFloat) / (maxOverlap.toFloat)
      coord * coord
    }
  }
  trait reciprocalCoord { this: LuceneSimilarity =>
    override def coord(overlap: Int, maxOverlap: Int) = 1.0f / ((1 + maxOverlap - overlap).toFloat)
  }

  private[this] val similarities = {
    var m = Map.empty[String, LuceneSimilarity]
    m += ("default" -> new DefaultSimilarity with noCoord)
    m += ("propotionalCoord" -> new DefaultSimilarity with proportionalCoord)
    m += ("squaredCoord" -> new DefaultSimilarity with squaredCoord)
    m += ("reciprocalCoord" -> new DefaultSimilarity with reciprocalCoord)
    m += ("noTF" -> new DefaultSimilarity with noTF with noCoord)
    m += ("noTF-propotionalCoord" -> new DefaultSimilarity with proportionalCoord)
    m += ("noTF-squaredCoord" -> new DefaultSimilarity with noTF with squaredCoord)
    m += ("noTF-reciprocalCoord" -> new DefaultSimilarity with noTF with reciprocalCoord)
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