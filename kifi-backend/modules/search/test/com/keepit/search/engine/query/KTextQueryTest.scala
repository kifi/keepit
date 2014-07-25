package com.keepit.search.engine.query

import org.apache.lucene.index.Term
import org.apache.lucene.search.TermQuery
import org.specs2.mutable.Specification
import com.keepit.common.db.Id
import com.keepit.search.index.VolatileIndexDirectory
import com.keepit.search.Tst
import com.keepit.search.TstIndexer

class KTextQueryTest extends Specification {

  val indexer = new TstIndexer(new VolatileIndexDirectory)
  Array("abc def", "abc def", "abc def", "abc ghi", "abc jkl").zip(Array("", "", "", "mno", "mno")).zipWithIndex.map {
    case ((text, fallbackText), id) =>
      indexer.index(Id[Tst](id), text, fallbackText)
  }

  "KTextQuery" should {
    "not fail even when there is no subquery" in {
      val q = new KTextQuery
      indexer.getPersonalizedSearcher(Set(0L)).search(q).map(_.id).toSet === Set.empty[Long]
    }

    "search using main query" in {
      val q0 = new KTextQuery
      q0.addQuery(new TermQuery(new Term("c", "def")))
      indexer.getPersonalizedSearcher(Set(0L)).search(q0).map(_.id).toSet === Set(0L, 1L, 2L)

      val q1 = new KTextQuery
      q1.addQuery(new TermQuery(new Term("c", "def")))
      q1.addQuery(new TermQuery(new Term("c", "ghi")))
      indexer.getPersonalizedSearcher(Set(0L)).search(q1).map(_.id).toSet === Set(0L, 1L, 2L, 3L)
    }

    "score using main query and semantic vector query" in {
      val q0 = new KTextQuery
      q0.addQuery(new TermQuery(new Term("c", "abc")))
      q0.setSemanticBoost(1.0f)
      q0.addSemanticVectorQuery("sv", "abc")
      indexer.getPersonalizedSearcher(Set(3L)).search(q0).head.id === 3L
      indexer.getPersonalizedSearcher(Set(4L)).search(q0).head.id === 4L
    }

    "disable semantic vector query when not available" in {
      val q0 = new KTextQuery
      q0.addQuery(new TermQuery(new Term("c", "abc")))
      q0.setSemanticBoost(1.0f)
      q0.addSemanticVectorQuery("sv", "def")
      indexer.getSearcher.search(q0).map(_.id).toSet === Set(0L, 1L, 2L, 3L, 4L)
    }
  }
}
