package com.keepit.search.engine.query.core

import java.util.{ Set => JSet }

import org.apache.lucene.index.{ AtomicReaderContext, IndexReader, Term }
import org.apache.lucene.search._
import org.apache.lucene.util.Bits

class NullQuery() extends Query {

  def project(fields: Set[String]) = this

  override def extractTerms(out: JSet[Term]): Unit = {}

  override def rewrite(reader: IndexReader): Query = this

  override def createWeight(searcher: IndexSearcher): Weight = new NullWeight(this)

  override def clone(): Query = new NullQuery()

  override def toString(field: String): String = s"ZeroScoreQuery()"

  override def hashCode(): Int = {
    "ZeroScoreQuery".hashCode() ^ java.lang.Float.floatToRawIntBits(getBoost())
  }

  override def equals(obj: Any): Boolean = {
    obj match {
      case other: NullQuery => getBoost == other.getBoost
      case _ => false
    }
  }
}

class NullWeight(query: NullQuery) extends Weight {

  override def getQuery(): Query = query

  override def getValueForNormalization() = query.getBoost()

  override def normalize(norm: Float, topLevelBoost: Float): Unit = {}

  override def explain(context: AtomicReaderContext, doc: Int) = {
    val result = new ComplexExplanation()
    result.setDescription("fixed score, doesn't match id %d".format(doc))
    result.setValue(0)
    result.setMatch(false)
    result
  }

  override def scorer(context: AtomicReaderContext, liveDocs: Bits): Scorer = null
}
