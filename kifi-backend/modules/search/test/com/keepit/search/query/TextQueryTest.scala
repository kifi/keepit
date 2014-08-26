package com.keepit.search.query

import org.apache.lucene.index.Term
import org.apache.lucene.search.TermQuery
import org.specs2.mutable.Specification
import com.keepit.common.db.Id
import com.keepit.search.index.VolatileIndexDirectory
import com.keepit.search.Tst
import com.keepit.search.TstIndexer

class TextQueryTest extends Specification {

  val indexer = new TstIndexer(new VolatileIndexDirectory)
  Array("abc def", "abc def", "abc def", "abc ghi", "abc jkl").zip(Array("", "", "", "mno", "mno")).zipWithIndex.map {
    case ((text, fallbackText), id) =>
      indexer.index(Id[Tst](id), text, fallbackText)
  }

  "TextQuery" should {
    "not fail even when there is no subquery" in {
      val q = new TextQuery
      indexer.getSearcher.searchAll(q).map(_.id).toSet === Set.empty[Long]
    }

    "search using regular query" in {
      val q0 = new TextQuery
      q0.addRegularQuery(new TermQuery(new Term("c", "def")))
      indexer.getSearcher.searchAll(q0).map(_.id).toSet === Set(0L, 1L, 2L)

      val q1 = new TextQuery
      q1.addRegularQuery(new TermQuery(new Term("c", "def")))
      q1.addRegularQuery(new TermQuery(new Term("c", "ghi")))
      indexer.getSearcher.searchAll(q1).map(_.id).toSet === Set(0L, 1L, 2L, 3L)
    }

    "search using personal query" in {
      val q0 = new TextQuery
      q0.addPersonalQuery(new TermQuery(new Term("c", "def")))
      indexer.getSearcher.searchAll(q0).map(_.id).toSet === Set(0L, 1L, 2L)

      val q1 = new TextQuery
      q1.addPersonalQuery(new TermQuery(new Term("c", "def")))
      q1.addPersonalQuery(new TermQuery(new Term("c", "ghi")))
      indexer.getSearcher.searchAll(q1).map(_.id).toSet === Set(0L, 1L, 2L, 3L)
    }

    "search using both regular and personal query" in {
      val q0 = new TextQuery
      q0.addRegularQuery(new TermQuery(new Term("c", "def")))
      q0.addPersonalQuery(new TermQuery(new Term("c", "ghi")))
      indexer.getSearcher.searchAll(q0).map(_.id).toSet === Set(0L, 1L, 2L, 3L)
    }

    "score using all queries" in {
      val q0 = new TextQuery
      q0.addRegularQuery(new TermQuery(new Term("c", "abc")))
      q0.addPersonalQuery(new TermQuery(new Term("p", "xyz"))) //no hit
      indexer.getSearcher.searchAll(q0).map(_.score).toSet.size === 1 // all scores are same
    }
  }
}
