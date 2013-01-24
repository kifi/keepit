package com.keepit.search.query

import com.keepit.search.index.Searcher
import org.apache.lucene.search.Query
import org.apache.lucene.search.{Searcher=>LuceneSearcher}
import org.apache.lucene.search.Weight

abstract class Query2 extends Query {
  override def createWeight(searcher: LuceneSearcher): Weight = createWeight2(searcher.asInstanceOf[Searcher])
  def createWeight2(searcher: Searcher): Weight

}
