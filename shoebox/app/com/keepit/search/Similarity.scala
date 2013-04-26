package com.keepit.search

import com.keepit.common.logging.Logging
import org.apache.lucene.search.CollectionStatistics
import org.apache.lucene.search.Explanation
import org.apache.lucene.search.TermStatistics
import org.apache.lucene.search.similarities.DefaultSimilarity

object Similarity extends Logging {
  // TF resilient to big documents (high term freq) and spam.
  trait NewTF extends DefaultSimilarity {
    override def tf(freq: Int) = 1.0f - (1.0f / (freq + 1.0f))
  }
  // disables TF
  trait NoTF extends DefaultSimilarity {
    override def tf(freq: Int) = 1.0f
  }

  // disables coord factor in a boolean query
  trait NoCoord extends DefaultSimilarity {
    override def coord(overlap: Int, maxOverlap: Int) = 1.0f
  }

  // IDF based on term's docCount rather than maxDoc.
  // This addresses the issue of over-estimated IDFs for fields from a personal index
  trait DocCountBasedIDF extends DefaultSimilarity {
    override def idfExplain(collectionStats: CollectionStatistics, termStats: TermStatistics): Explanation = {
      val df = termStats.docFreq
      val docCount = collectionStats.docCount
      val numDocs = if (docCount < 0) collectionStats.maxDoc else docCount
      new Explanation(idf(df, numDocs), s"idf(docFreq=${df}, numDocs=${numDocs}")
    }
  }

  private[this] val similarities: Map[String, DefaultSimilarity] = Map(
    ("default" -> new DefaultSimilarity with DocCountBasedIDF with NewTF with NoCoord),
    ("noTF" -> new DefaultSimilarity with DocCountBasedIDF with NoTF with NoCoord)
  )

  def apply(name: String) = {
    def fallback = {
      log.warn("Similarity(%s) not found".format(name))
      similarities("default")
    }
    similarities.getOrElse(name, fallback)
  }
}
