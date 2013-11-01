package com.keepit.search.query

import com.keepit.common.logging.Logging
import com.keepit.search.SemanticVector
import com.keepit.search.index.Searcher
import com.keepit.search.SemanticVectorEnum
import com.keepit.search.query.QueryUtil._
import org.apache.lucene.index.AtomicReader
import org.apache.lucene.index.AtomicReaderContext
import org.apache.lucene.index.DocsAndPositionsEnum
import org.apache.lucene.index.FilterAtomicReader.FilterDocsAndPositionsEnum
import org.apache.lucene.index.IndexReader
import org.apache.lucene.index.Term
import org.apache.lucene.search.Query
import org.apache.lucene.search.Scorer
import org.apache.lucene.search.Weight
import org.apache.lucene.search.ComplexExplanation
import org.apache.lucene.search.DocIdSetIterator
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import org.apache.lucene.search.Explanation
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.similarities.Similarity
import org.apache.lucene.util.Bits
import org.apache.lucene.util.BytesRef
import org.apache.lucene.util.PriorityQueue
import org.apache.lucene.util.ToStringUtils
import java.util.{Set => JSet}
import java.lang.{Float => JFloat}
import scala.collection.JavaConversions._
import scala.collection.mutable.ArrayBuffer
import scala.math._

object SemanticVectorQuery {
  def apply(term: Term) = new SemanticVectorQuery(term)
}

class SemanticVectorQuery(val term: Term) extends Query {

  override def createWeight(searcher: IndexSearcher): Weight = {
    new SemanticVectorWeight(this, searcher.asInstanceOf[Searcher])
  }

  override def rewrite(reader: IndexReader): Query = this

  override def extractTerms(out: JSet[Term]): Unit = out.add(term)

  override def toString(s: String) = {
    "semanticvector(%s)%s".format(term.toString(), ToStringUtils.boost(getBoost()))
  }

  override def equals(obj: Any): Boolean = obj match {
    case svq: SemanticVectorQuery => (term == svq.term && getBoost() == svq.getBoost())
    case _ => false
  }

  override def hashCode(): Int = term.hashCode() + JFloat.floatToRawIntBits(getBoost())
}

class SemanticVectorWeight(query: SemanticVectorQuery, searcher: Searcher) extends Weight {

  private[this] var value = 0.0f

  val term = query.term

  override def getQuery() = query
  override def scoresDocsOutOfOrder() = false

  override def getValueForNormalization() = {
    val boost = query.getBoost()
    boost * boost
  }

  override def normalize(norm: Float, topLevelBoost: Float) {
    searcher.addContextTerm(query.term)
    value = query.getBoost * norm * topLevelBoost
  }

  override def explain(context: AtomicReaderContext, doc: Int) = {
    val reader = context.reader
    val sc = scorer(context, true, false, reader.getLiveDocs);
    val exists = (sc != null && sc.advance(doc) == doc);

    val result = new ComplexExplanation()
    if (exists) {
      result.setDescription("semantic vector (%s):".format(query.term))
      result.setValue(sc.score)
      result.setMatch(true)
    } else {
      result.setDescription("semantic vector (%s), doesn't match id %d".format(query.term, doc))
      result.setValue(0)
      result.setMatch(false)
    }
    result
  }

  override def scorer(context: AtomicReaderContext, scoreDocsInOrder: Boolean, topScorer: Boolean, acceptDocs: Bits): Scorer = {
    val contextSketch = searcher.getContextSketch
    val vector = searcher.getSemanticVector(query.term, contextSketch, 0.618f)

    val tp = searcher.getSemanticVectorEnum(context, query.term, context.reader.getLiveDocs)
    if (tp != null) new SemanticVectorScorerImpl(this, tp, vector, value) else new EmptySemanticVectorScorerImpl(this, vector)
  }
}

abstract class SemanticVectorScorer(weight: SemanticVectorWeight, vector: SemanticVector) extends Scorer(weight) {
  val term = weight.term
  def getQuerySemanticVector(): SemanticVector = vector
  def getSemanticVectorBytesRef(): BytesRef = null
}

class SemanticVectorScorerImpl(weight: SemanticVectorWeight, tp: SemanticVectorEnum, vector: SemanticVector, value: Float) extends SemanticVectorScorer(weight, vector) with Logging {
  private[this] var doc = -1
  private[this] var scoredDoc = -1
  private[this] var scoreValue = 0f

  def docID(): Int = doc

  def nextDoc(): Int = {
    doc = tp.nextDoc()
    doc
  }

  def advance(target: Int): Int = {
    doc = tp. advance(target)
    doc
  }

  def score(): Float = {
    if (scoredDoc < doc) {
      scoredDoc = doc
      val payload = tp.getSemanticVector()
      scoreValue = if (payload != null) {
        vector.similarity(payload.bytes, payload.offset) * value
      } else {
        0.0f
      }
    }
    scoreValue
  }

  override def freq(): Int = 1

  override def getSemanticVectorBytesRef(): BytesRef = {
    if (scoredDoc < doc) {
      scoredDoc = doc
      tp.getSemanticVector()
    } else {
      log.error("called getSemanticVectorBytesRef again!")
      null
    }
  }
}

class EmptySemanticVectorScorerImpl(weight: SemanticVectorWeight, vector: SemanticVector) extends SemanticVectorScorer(weight, vector) {
  override def docID(): Int = NO_MORE_DOCS
  override def nextDoc(): Int = NO_MORE_DOCS
  override def advance(target: Int): Int = NO_MORE_DOCS
  override def score(): Float = 0.0f
  override def freq(): Int = 0
}

