package com.keepit.search

import com.keepit.search.index.PersonalizedSearcher
import com.keepit.search.index.Searcher
import com.keepit.search.graph.URIGraphSearcher
import com.keepit.search.query.IdSetFilter
import com.keepit.search.query.QueryUtil
import com.keepit.search.query.SemanticVectorExtractorQuery
import com.keepit.search.query.SemanticVectorExtractorScorer
import com.keepit.search.query.TextQuery
import org.apache.lucene.index.Term
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import org.apache.lucene.search.Query

object SemanticVariance {
  /**
   * vects: a collection of 128-bit vectors. We measure the variance of each bit,
   * and take the average. This measures overall randomness of input semantic vectors.
   */
  def avgBitVariance(composer: SemanticVectorComposer, numHits: Int): Float = {
    val numVects = composer.numInputs
    val numMissing = numHits - numVects
    var sumOfVar = 0.0f
    var i = 0
    if (numVects > 0) {
      while (i < SemanticVector.vectorSize) {
        val c = composer.getCount(i).toFloat + numMissing.toFloat * 0.5f
        val p = (c / numHits.toFloat)  // empirical probability that position i takes value 1
        sumOfVar += p * (1 - p)                        // variance of Bernoulli distribution.
        i += 1
      }
      sumOfVar / SemanticVector.vectorSize.toFloat
    } else {
      0.5f * 0.5f
    }
  }

  /**
   * Given a hitList, find the variance of the semantic vectors.
   */
  def svVariance(textQueries: Seq[TextQuery], hitList: List[MutableArticleHit], personalizedSearcher: Option[PersonalizedSearcher]): Float = {
    svVariance(textQueries, hitList.map(_.id).toSet, personalizedSearcher)
  }

  def svVariance(textQueries: Seq[TextQuery], ids: Set[Long], personalizedSearcher: Option[PersonalizedSearcher]): Float = {
    val uriIdFilter = new IdSetFilter(ids)
    val hitSize = uriIdFilter.ids.size
    var composers = Map.empty[Term, SemanticVectorComposer]

    personalizedSearcher.map{ searcher =>
      textQueries.foreach{ q =>
        val extractorQuery = q.getSemanticVectorExtractorQuery()
          searcher.doSearch(extractorQuery, uriIdFilter){ (scorer, iterator, reader) =>
          if (scorer != null && iterator != null) {
            val extractor = scorer.asInstanceOf[SemanticVectorExtractorScorer]
            while (iterator.nextDoc() < NO_MORE_DOCS) {
              val doc = iterator.docID()
              if (extractor.docID < doc) extractor.advance(doc)
              if (extractor.docID == doc) extractor.processSemanticVector{ (term: Term, bytes: Array[Byte], offset: Int, length: Int) =>
                composers.getOrElse(term, {
                  val composer = new SemanticVectorComposer
                  composers += (term -> composer)
                  composer
                }).add(bytes, offset, length, 1)
              }
            }
          }
        }
      }
    }

    val sum = composers.valuesIterator.foldLeft(0.0f){ (sum, composer) =>
      // semantic vector v of terms will be concatenated from semantic vector v_i from each term
      // avg bit variance of v is the avg of avgBitVariance of each v_i
      sum + avgBitVariance(composer, hitSize)
    }
    val cnt = composers.size
    if (cnt > 0) (sum / cnt.toFloat) else -1.0f
  }
}

