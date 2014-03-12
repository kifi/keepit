package com.keepit.search.query

import org.specs2.mutable._
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.Term
import org.apache.lucene.util.Version
import com.keepit.common.db.Id
import com.keepit.common.db.SequenceNumber
import com.keepit.search.index._
import com.keepit.search.PersonalizedSearcher
import com.keepit.search.semantic.SemanticVectorBuilder
import com.keepit.search.Tst
import com.keepit.search.TstIndexer

class SemanticVectorQueryTest extends Specification {

  val config = new IndexWriterConfig(Version.LUCENE_41, DefaultAnalyzer.defaultAnalyzer)

  val indexer = new TstIndexer(new VolatileIndexDirectoryImpl, config)
  Array("abc", "abc def", "abc def ghi", "def ghi").zip(Array("", "", "", "jkl")).zipWithIndex.map{ case ((text, fallbackText), id) =>
    indexer.index(Id[Tst](id), text, fallbackText)
  }

  "SemanticVectorQuery" should {
    "score using a personalized vector" in {
      var q = SemanticVectorQuery(new Term("sv", "abc"))

      val searcher0 = indexer.getPersonalizedSearcher(Set(0L))
      val searcher1 = indexer.getPersonalizedSearcher(Set(1L))
      val searcher2 = indexer.getPersonalizedSearcher(Set(2L))
      val searcher3 = indexer.getPersonalizedSearcher(Set(3L))
      searcher0.search(q).head.id === 0
      searcher1.search(q).head.id === 1
      searcher2.search(q).head.id === 2
      searcher3.search(q).head.id === 0
    }
  }
}
