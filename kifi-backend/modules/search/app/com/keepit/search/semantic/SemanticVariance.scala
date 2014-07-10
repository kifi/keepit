package com.keepit.search.semantic

import com.keepit.search.PersonalizedSearcher
import com.keepit.search.query.IdSetFilter
import com.keepit.search.query.SemanticVectorExtractorScorer
import com.keepit.search.query.TextQuery
import org.apache.lucene.index.Term
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS

object SemanticVariance {
  /**
   * vects: a collection of 128-bit vectors. We measure the variance of each bit,
   * and take the average. This measures overall randomness of input semantic vectors.
   */
  def avgBitVariance(composer: SemanticVectorComposer): Float = {
    val numVects = composer.numInputs
    var sumOfVar = 0.0f
    var i = 0
    if (numVects > 0) {
      while (i < SemanticVector.vectorSize) {
        val p = (composer.getCount(i).toFloat / numVects) // empirical probability that position i takes value 1
        sumOfVar += p * (1 - p) // variance of Bernoulli distribution.
        i += 1
      }
      sumOfVar / SemanticVector.vectorSize.toFloat
    } else if (numVects == 0) {
      0.0f
    } else {
      0.5f * 0.5f
    }
  }

  /**
   * Given a hitList, find the variance of the semantic vectors.
   */
  def svVariance(textQueries: Seq[TextQuery], ids: Set[Long], personalizedSearcher: PersonalizedSearcher): Float = {
    val uriIdFilter = new IdSetFilter(ids)
    var composer = new SemanticVectorComposer

    textQueries.foreach { q =>
      val extractorQuery = q.getSemanticVectorExtractorQuery()
      personalizedSearcher.doSearch(extractorQuery, uriIdFilter) { (scorer, iterator, reader) =>
        if (scorer != null && iterator != null) {
          val extractor = scorer.asInstanceOf[SemanticVectorExtractorScorer]
          while (iterator.nextDoc() < NO_MORE_DOCS) {
            val doc = iterator.docID()
            if (extractor.docID < doc) extractor.advance(doc)
            if (extractor.docID == doc) extractor.processSemanticVector { (term: Term, bytes: Array[Byte], offset: Int, length: Int) =>
              composer.add(bytes, offset, length, 1)
            }
          }
        }
      }
    }
    avgBitVariance(composer)
  }
}

