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
      indexer.getPersonalizedSearcher(Set(0L)).searchAll(q).map(_.id).toSet === Set.empty[Long]
    }

    "search using subqueries" in {
      val q0 = new KTextQuery
      q0.addQuery(new TermQuery(new Term("c", "def")))
      indexer.getPersonalizedSearcher(Set(0L)).searchAll(q0).map(_.id).toSet === Set(0L, 1L, 2L)

      val q1 = new KTextQuery
      q1.addQuery(new TermQuery(new Term("c", "def")))
      q1.addQuery(new TermQuery(new Term("c", "ghi")))
      indexer.getPersonalizedSearcher(Set(0L)).searchAll(q1).map(_.id).toSet === Set(0L, 1L, 2L, 3L)
    }
  }
}
