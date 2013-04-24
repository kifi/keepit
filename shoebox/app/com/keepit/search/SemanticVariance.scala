package com.keepit.search

import com.keepit.search.query.QueryUtil
import com.keepit.search.index.Searcher
import com.keepit.search.graph.URIGraphSearcher
import org.apache.lucene.search.Query
import com.keepit.search.index.PersonalizedSearcher
import org.apache.lucene.index.Term

object SemanticVariance {
  /**
   * vects: a collection of 128-bit vectors. We measure the variance of each bit,
   * and take the average. This measures overall randomness of input semantic vectors.
   */
  def avgBitVariance(vects: Iterable[SemanticVector], numMissing: Int): Float = {
    val vectsCnt = vects.size
    if (vectsCnt > 0) {
      val composer = new SemanticVectorComposer
      vects.foreach(composer.add(_, 1))

      var sumOfVar = 0.0f
      var i = 0
      while (i < SemanticVector.vectorSize) {
        val c = composer.getCount(i).toFloat + numMissing.toFloat * 0.5f
        val p = (c / (vectsCnt + numMissing).toFloat)  // empirical probability that position i takes value 1
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
  def svVariance(query: Option[Query], hitList: List[MutableArticleHit], personalizedSearcher: Option[PersonalizedSearcher]) = {
    val uriIds = hitList.map(_.id).toSet
    val hitSize = uriIds.size
    var existCnt = List.empty[Int] // for each sv, we count how many docs contain it
    val variance = query.flatMap{ q =>
      personalizedSearcher.map{ searcher =>
        val terms = QueryUtil.getTerms("sv", q)
        var s = 0.0f
        var cnt = 0
        for (term <- terms) {
          val svMap = searcher.getSemanticVectors(term, uriIds)
          val missing = uriIds -- svMap.keySet
          var sv = svMap.values
          if (!missing.isEmpty) {
            val defaultSV = searcher.getSemanticVector(term)
            sv ++= searcher.filterByTerm(missing, new Term("title_stemmed", term.text)).iterator.map{ id => defaultSV }
          }

          existCnt = sv.size::existCnt
          // semantic vector v of terms will be concatenated from semantic vector v_i from each term
          // avg bit variance of v is the avg of avgBitVariance of each v_i
          s += avgBitVariance(sv, numMissing = (hitSize - sv.size))
          cnt += 1
        }
        if (hitSize == 1) 0.0f else if (cnt > 0) s / cnt.toFloat else -1.0f
      }
    }
    val existVar =  existenceVariance(existCnt, uriIds.size)
    (variance.getOrElse(-1.0f), existVar)
  }

}
