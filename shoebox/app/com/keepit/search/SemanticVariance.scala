package com.keepit.search

import com.keepit.search.query.QueryUtil
import com.keepit.search.index.Searcher
import com.keepit.search.graph.URIGraphSearcher
import org.apache.lucene.search.Query

object SemanticVariance {
  /**
   * vects: a collection of 128-bit vectors. We measure the variance of each bit,
   * and take the average. This measures overall randomness of input semantic vectors.
   */
  def avgBitVariance(vects: Iterable[SemanticVector]) = {
    if (vects.size > 0) {
      val composer = new SemanticVectorComposer
      vects.foreach(composer.add(_, 1))

      // qs.vec(i) + 0.5 = empirical probability that position i takes value 1.
      val qs = composer.getQuasiSketch
      val prob = for (i <- 0 until qs.vec.length) yield (qs.vec(i) + 0.5f)
      val sumOfVar = prob.foldLeft(0.0f)((sum: Float, p: Float) => sum + p * (1 - p)) // variance of Bernoulli distribution.
      Some(sumOfVar / qs.vec.length)
    } else {
      None
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
  def svVariance(query: Option[Query], hitList: List[MutableArticleHit], articleSearcher: Searcher, uriGraphSearcher: URIGraphSearcher) = {
    val svSearcher = new SemanticVectorSearcher(articleSearcher, uriGraphSearcher)
    val uriIds = hitList.map(_.id).toSet
    var existCnt = List.empty[Int]							// for each sv, we count how many docs contain it
    val variance = query.map { q =>
      val terms = QueryUtil.getTerms("sv", q)
      var s = 0.0f
      var cnt = 0
      for (term <- terms) {
        val sv = svSearcher.getSemanticVectors(term, uriIds).collect { case (id, vec) => vec }
        existCnt = sv.size::existCnt
        // semantic vector v of terms will be concatenated from semantic vector v_i from each term
        // avg bit variance of v is the avg of avgBitVariance of each v_i
        val variance = avgBitVariance(sv)
        variance match {
          case Some(v) => { cnt += 1; s += v }
          case None => None
        }

      }
      if (cnt > 0) s / cnt.toFloat else -1.0f
    }
    val existVar =  existenceVariance(existCnt, uriIds.size)
    (variance.getOrElse(-1.0f), existVar)
  }

}