package com.keepit.search.semantic

import org.specs2.mutable.Specification
import com.keepit.common.db.Id
import com.keepit.search.index._
import java.io.StringReader
import com.keepit.common.db.SequenceNumber
import com.keepit.search.query.TextQuery
import org.apache.lucene.search.TermQuery
import org.apache.lucene.index.Term
import com.keepit.search.PersonalizedSearcher
import com.keepit.search.TstIndexer
import com.keepit.search.Tst

class SemanticVarianceTest extends Specification {

  val indexer = new TstIndexer(new VolatileIndexDirectory)
  Array("abc def", "abc def", "abc def", "abc ghi", "abc jkl").zip(Array("", "", "", "mno", "mno")).zipWithIndex.map {
    case ((text, fallbackText), id) =>
      indexer.index(Id[Tst](id), text, fallbackText)
  }

  "SemanticVariance" should {
    "compute the variance with no personalization" in {
      val q = new TextQuery
      q.setSemanticBoost(1.0f)
      q.addSemanticVectorQuery("sv", "abc")

      val variance0 = SemanticVariance.svVariance(Seq(q), Set(0L, 1L, 2L), indexer.getPersonalizedSearcher(Set(0L)))
      val variance1 = SemanticVariance.svVariance(Seq(q), Set(0L, 1L, 2L), indexer.getPersonalizedSearcher(Set(1L)))
      val variance2 = SemanticVariance.svVariance(Seq(q), Set(2L, 3L, 4L), indexer.getPersonalizedSearcher(Set(1L)))

      (variance0 == variance1) === true
      (variance1 < variance2) === true
    }

    "compute the variance with personalization" in {
      val q1 = new TextQuery
      q1.setSemanticBoost(1.0f)
      q1.addSemanticVectorQuery("sv", "def")
      val q2 = new TextQuery
      q2.addPersonalQuery(new TermQuery(new Term("p", "mno")))
      q2.setSemanticBoost(1.0f)
      q2.addSemanticVectorQuery("sv", "def")

      val variance0 = SemanticVariance.svVariance(Seq(q1), Set(0L, 1L, 2L, 3L, 4L), indexer.getPersonalizedSearcher(Set(0L)))
      val variance1 = SemanticVariance.svVariance(Seq(q2), Set(0L, 1L, 2L, 3L, 4L), indexer.getPersonalizedSearcher(Set(0L)))
      val variance2 = SemanticVariance.svVariance(Seq(q2), Set(0L, 1L, 2L, 3L, 4L), indexer.getPersonalizedSearcher(Set(3L)))

      (variance0 == variance1) === true
      (variance1 < variance2) === true
    }

    "compute the variance with multiple semanctic vector queries" in {
      val q1 = new TextQuery
      q1.setSemanticBoost(1.0f)
      q1.addSemanticVectorQuery("sv", "abc")
      q1.addSemanticVectorQuery("sv", "def")
      val q2 = new TextQuery
      q2.setSemanticBoost(1.0f)
      q2.addSemanticVectorQuery("sv", "abc")
      q2.addSemanticVectorQuery("sv", "ghi")

      val variance0 = SemanticVariance.svVariance(Seq(q1), Set(0L, 1L, 2L, 3L, 4L), indexer.getPersonalizedSearcher(Set(0L)))
      val variance1 = SemanticVariance.svVariance(Seq(q2), Set(0L, 1L, 2L, 3L, 4L), indexer.getPersonalizedSearcher(Set(0L)))

      (variance0 < variance1) === true
    }

    "compute the variance with no semanctic vector queries" in {
      val q1 = new TextQuery
      val q2 = new TextQuery
      q2.addRegularQuery(new TermQuery(new Term("c", "abc")))
      val q3 = new TextQuery
      q3.addPersonalQuery(new TermQuery(new Term("c", "abc")))

      val variance0 = SemanticVariance.svVariance(Seq(q1), Set(0L, 1L, 2L, 3L, 4L), indexer.getPersonalizedSearcher(Set(0L)))
      val variance1 = SemanticVariance.svVariance(Seq(q2), Set(0L, 1L, 2L, 3L, 4L), indexer.getPersonalizedSearcher(Set(0L)))
      val variance2 = SemanticVariance.svVariance(Seq(q3), Set(0L, 1L, 2L, 3L, 4L), indexer.getPersonalizedSearcher(Set(0L)))

      variance0 === 0.0f
      variance1 === 0.0f
      variance2 === 0.0f
    }
  }
}
