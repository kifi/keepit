package com.keepit.search.engine.query.core

import com.keepit.common.db.Id
import com.keepit.search.{ Tst, TstIndexer }
import com.keepit.search.index.VolatileIndexDirectory
import org.apache.lucene.index.Term
import org.apache.lucene.search.TermQuery
import org.specs2.mutable.Specification

class KTextQueryTest extends Specification {

  val indexer = new TstIndexer(new VolatileIndexDirectory)
  Array("abc def", "abc def", "abc def", "abc ghi", "abc jkl").zip(Array("", "", "", "mno", "mno")).zipWithIndex.map {
    case ((text, fallbackText), id) =>
      indexer.index(Id[Tst](id), text, fallbackText)
  }

  "KTextQuery" should {
    "not fail even when there is no subquery" in {
      val q = new KTextQuery("")
      indexer.getSearcher.searchAll(q).map(_.id).toSet === Set.empty[Long]
    }

    "search using subqueries" in {
      val q0 = new KTextQuery("")
      q0.addQuery(new TermQuery(new Term("c", "def")))
      indexer.getSearcher.searchAll(q0).map(_.id).toSet === Set(0L, 1L, 2L)

      val q1 = new KTextQuery("")
      q1.addQuery(new TermQuery(new Term("c", "def")))
      q1.addQuery(new TermQuery(new Term("c", "ghi")))
      indexer.getSearcher.searchAll(q1).map(_.id).toSet === Set(0L, 1L, 2L, 3L)
    }
  }
}
