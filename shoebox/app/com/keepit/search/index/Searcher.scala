package com.keepit.search.index

import org.apache.lucene.index.IndexReader
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.Query
import org.apache.lucene.search.Scorer
import scala.collection.mutable.ArrayBuffer

class Searcher(val indexReader: IndexReader, val idMapper: IdMapper) extends IndexSearcher(indexReader) {

  // search: hits are ordered by score
  def search(query: Query): Seq[Hit] = {
    doSearch(query){ scorer =>
      val hitBuf = new ArrayBuffer[Hit]()
      var doc = scorer.nextDoc()
      while (doc != NO_MORE_DOCS) {
        var score = scorer.score()
        hitBuf += Hit(idMapper.getId(doc), score)
        doc = scorer.nextDoc()
      }
      hitBuf.sortWith((a, b) => a.score >= b.score).toSeq
    }
  }
  
  def doSearch[R](query: Query)(f:Scorer => R) = {
    val rewrittenQuery = rewrite(query)
    val weight = createNormalizedWeight(rewrittenQuery)
    val scorer = weight.scorer(indexReader, true, true)
    f(scorer)
  }
}
  
case class Hit(id: Long, score: Float)

