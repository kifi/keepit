package com.keepit.search.query

import org.apache.lucene.index.Term
import org.apache.lucene.search.TermQuery
import org.specs2.mutable.Specification
import com.keepit.common.db.Id
import com.keepit.common.db.SequenceNumber
import com.keepit.search.PersonalizedSearcher
import com.keepit.search.semantic.SemanticVectorBuilder
import com.keepit.search.index.DefaultAnalyzer
import com.keepit.search.index.IndexDirectory
import com.keepit.search.index.Indexable
import com.keepit.search.index.Indexer
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
      indexer.getPersonalizedSearcher(Set(0L)).searchAll(q).map(_.id).toSet === Set.empty[Long]
    }

    "search using regular query" in {
      val q0 = new TextQuery
      q0.addRegularQuery(new TermQuery(new Term("c", "def")))
      indexer.getPersonalizedSearcher(Set(0L)).searchAll(q0).map(_.id).toSet === Set(0L, 1L, 2L)

      val q1 = new TextQuery
      q1.addRegularQuery(new TermQuery(new Term("c", "def")))
      q1.addRegularQuery(new TermQuery(new Term("c", "ghi")))
      indexer.getPersonalizedSearcher(Set(0L)).searchAll(q1).map(_.id).toSet === Set(0L, 1L, 2L, 3L)
    }

    "search using personal query" in {
      val q0 = new TextQuery
      q0.addPersonalQuery(new TermQuery(new Term("c", "def")))
      indexer.getPersonalizedSearcher(Set(0L)).searchAll(q0).map(_.id).toSet === Set(0L, 1L, 2L)

      val q1 = new TextQuery
      q1.addPersonalQuery(new TermQuery(new Term("c", "def")))
      q1.addPersonalQuery(new TermQuery(new Term("c", "ghi")))
      indexer.getPersonalizedSearcher(Set(0L)).searchAll(q1).map(_.id).toSet === Set(0L, 1L, 2L, 3L)
    }

    "search using both regular and personal query" in {
      val q0 = new TextQuery
      q0.addRegularQuery(new TermQuery(new Term("c", "def")))
      q0.addPersonalQuery(new TermQuery(new Term("c", "ghi")))
      indexer.getPersonalizedSearcher(Set(0L)).searchAll(q0).map(_.id).toSet === Set(0L, 1L, 2L, 3L)
    }

    "score using regular query and semantic vector query" in {
      val q0 = new TextQuery
      q0.addRegularQuery(new TermQuery(new Term("c", "abc")))
      q0.setSemanticBoost(1.0f)
      q0.addSemanticVectorQuery("sv", "abc")
      indexer.getPersonalizedSearcher(Set(3L)).searchAll(q0).head.id === 3L
      indexer.getPersonalizedSearcher(Set(4L)).searchAll(q0).head.id === 4L
    }

    "score using personal query and semantic vector query" in {
      val q0 = new TextQuery
      q0.addPersonalQuery(new TermQuery(new Term("c", "abc")))
      q0.setSemanticBoost(1.0f)
      q0.addSemanticVectorQuery("sv", "abc")
      indexer.getPersonalizedSearcher(Set(3L)).searchAll(q0).head.id === 3L
      indexer.getPersonalizedSearcher(Set(4L)).searchAll(q0).head.id === 4L
    }

    "score using all queries" in {
      val q0 = new TextQuery
      q0.addRegularQuery(new TermQuery(new Term("c", "abc")))
      q0.addPersonalQuery(new TermQuery(new Term("p", "xyz"))) //no hit
      q0.setSemanticBoost(1.0f)
      q0.addSemanticVectorQuery("sv", "mno")
      indexer.getPersonalizedSearcher(Set(0L)).searchAll(q0).map(_.score).toSet.size === 1 // all scores are same

      val q1 = new TextQuery
      q1.addRegularQuery(new TermQuery(new Term("c", "abc")))
      q1.addPersonalQuery(new TermQuery(new Term("p", "mno")))
      q1.setSemanticBoost(1.0f)
      q1.addSemanticVectorQuery("sv", "mno")
      val result = indexer.getPersonalizedSearcher(Set(0L)).searchAll(q1)
      result.map(_.score).toSet.size !== 1 // there are different scores
      result.take(2).map(_.id).toSet === Set(3L, 4L)
    }
  }
}
