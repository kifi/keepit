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
   *  measure the randomness of the existence of a term in the hits
   *  existCnt: each cnt in the List is the number of hits that contain a term
   *  totalCnt: total number of hits
   */
  def existenceVariance(existCnt: List[Int], totalCnt: Int) = {
    if (totalCnt == 0) -1.0f
    else existCnt.foldLeft(0.0f)( (sum, cnt) => { val p = cnt*1.0f/totalCnt; sum + p * (1-p) } )
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
    var existCnt = List.empty[Int] // for each sv, we count how many docs contain it
    var composers = Map.empty[Term, SemanticVectorComposer]

    textQueries.foreach{ q =>
      val extractorQuery = q.getSemanticVectorExtractorQuery()
      personalizedSearcher.map{ searcher =>
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

