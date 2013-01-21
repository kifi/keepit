package com.keepit.search.query

import com.keepit.search.index.Searcher
import org.apache.lucene.index.IndexReader
import org.apache.lucene.index.Term
import org.apache.lucene.index.TermPositions
import org.apache.lucene.search.Query
import org.apache.lucene.search.Scorer
import org.apache.lucene.search.Weight
import org.apache.lucene.search.{Searcher => LuceneSearcher}
import org.apache.lucene.search.ComplexExplanation
import org.apache.lucene.search.Explanation
import org.apache.lucene.search.Similarity
import org.apache.lucene.search.DocIdSetIterator
import org.apache.lucene.util.PriorityQueue
import org.apache.lucene.util.ToStringUtils
import java.util.{Set => JSet}
import java.lang.{Float => JFloat}
import scala.collection.JavaConversions._
import scala.math._

object DummySemanticVectorQuery {
  def apply(fieldName: String, terms: Set[Term]) = new DummySemanticVectorQuery(terms.map{ term => new Term(fieldName, term.text) })
}

class DummySemanticVectorQuery(val terms: Set[Term]) extends Query {

  override def createWeight(searcher: LuceneSearcher): Weight = new DummySemanticVectorWeight(this, searcher)

  override def rewrite(reader: IndexReader): Query = this

  override def extractTerms(out: JSet[Term]): Unit = out.addAll(terms)

  override def toString(s: String) = "dummysemanticvector(%s)%s".format(terms.mkString(","),ToStringUtils.boost(getBoost()))

  override def equals(obj: Any): Boolean = obj match {
    case svq: DummySemanticVectorQuery => (terms == svq.terms && getBoost() == svq.getBoost())
    case _ => false
  }

  override def hashCode(): Int = terms.hashCode() + JFloat.floatToRawIntBits(getBoost())
}

class DummySemanticVectorWeight(query: DummySemanticVectorQuery, searcher: LuceneSearcher) extends Weight {

  private[this] val termIdfList = {
    query.terms.foldLeft(List.empty[(Term, Float)]){ (list, term) =>
      val idf = searcher.getSimilarity.idf(searcher.docFreq(term), searcher.maxDoc)
      (term, idf)::list
    }
  }

  override def getValue() = query.getBoost()
  override def scoresDocsOutOfOrder() = false

  override def sumOfSquaredWeights() = {
    val sum = termIdfList.foldLeft(0.0f){ (s, t) => s + t._2 * t._2 }
    val value = query.getBoost()
    (sum * value * value)
  }

  override def normalize(norm: Float) { }

  override def explain(reader: IndexReader, doc: Int) = {
    val result = new ComplexExplanation()
    result.setDescription("dummy semantic vector (%)".format(query.terms.mkString(",")))
    result.setValue(0)
    result.setMatch(false)
    result
  }

  def getQuery() = query

  override def scorer(reader: IndexReader, scoreDocsInOrder: Boolean, topScorer: Boolean): Scorer = null
}