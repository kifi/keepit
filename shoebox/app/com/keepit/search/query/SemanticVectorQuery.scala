package com.keepit.search.query

import com.keepit.search.SemanticVector
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

object SemanticVectorQuery {
  def apply(fieldName: String, terms: Set[Term]) = new SemanticVectorQuery(terms.map{ term => new Term(fieldName, term.text) })
}

class SemanticVectorQuery(val terms: Set[Term]) extends Query2 {

  override def createWeight2(searcher: Searcher): Weight = new SemanticVectorWeight(this, searcher)

  override def rewrite(reader: IndexReader): Query = this

  override def extractTerms(out: JSet[Term]): Unit = out.addAll(terms)

  override def toString(s: String) = "semanticvector(%s)%s".format(terms.mkString(","),ToStringUtils.boost(getBoost()))

  override def equals(obj: Any): Boolean = obj match {
    case svq: SemanticVectorQuery => (terms == svq.terms && getBoost() == svq.getBoost())
    case _ => false
  }

  override def hashCode(): Int = terms.hashCode() + JFloat.floatToRawIntBits(getBoost())
}

class SemanticVectorWeight(query: SemanticVectorQuery, searcher: Searcher) extends Weight {

  private[this] var termList = {
    val terms = query.terms
    terms.foldLeft(List.empty[(Term, Array[Byte], Float)]){ (list, term) =>
      val vector = searcher.getSemanticVector(term)
      val idf = searcher.idf(term)
      (term, vector, idf)::list
    }
  }

  override def getQuery() = query
  override def getValue() = query.getBoost()
  override def scoresDocsOutOfOrder() = false

  override def sumOfSquaredWeights() = {
    val sum = termList.foldLeft(0.0f){ case (sum, (_, _, idf)) => sum + idf * idf }
    val value = query.getBoost()
    (sum * value * value)
  }

  override def normalize(norm: Float) {
    val n = norm * getValue()
    termList = termList.map{ case (term, vector, idf) => (term, vector, idf * n) }
  }

  override def explain(reader: IndexReader, doc: Int) = {
    val sc = scorer(reader, true, false);
    val exists = (sc != null && sc.advance(doc) == doc);

    val result = new ComplexExplanation()
    if (exists) {
      result.setDescription("semantic vector (%s), sum of:".format(query.terms.mkString(",")))
      result.setValue(sc.score)
      result.setMatch(true)

      termList.map{ case (term, vector, value) =>
        explainTerm(term, reader, vector, value, doc) match {
          case Some(detail) => result.addDetail(detail)
          case None =>
        }
      }
    } else {
      result.setDescription("semantic vector (%s), doesn't match id %d".format(query.terms.mkString(","), doc))
      result.setValue(0)
      result.setMatch(false)
    }
    result
  }

  private def explainTerm(term: Term, reader: IndexReader, vector: Array[Byte], value: Float, doc: Int) = {
    val dv = new DocAndVector(reader.termPositions(term), vector, value)
    dv.fetchDoc(doc)
    if (dv.doc == doc && value > 0.0f) {
      val sc = dv.scoreAndNext()
      val expl = new ComplexExplanation()
      expl.setDescription("term(%s)".format(term.toString))
      expl.addDetail(new Explanation(sc/value, "similarity"))
      expl.addDetail(new Explanation(value, "boost"))
      expl.setValue(sc)
      Some(expl)
    } else {
      None
    }
  }

  override def scorer(reader: IndexReader, scoreDocsInOrder: Boolean, topScorer: Boolean): Scorer = {
    if (!termList.isEmpty) {
      val tps = termList.map{ case (term, vector, value) => new DocAndVector(reader.termPositions(term), vector, value) }
      new SemanticVectorScorer(this, tps)
    } else {
      QueryUtil.emptyScorer(this)
    }
  }
}

private[query] final class DocAndVector(tp: TermPositions, vector: Array[Byte], weight: Float) {
  var doc = -1

  private[this] var curVec = new Array[Byte](SemanticVector.arraySize)

  def fetchDoc(target: Int) {
    doc = if (tp.skipTo(target)) tp.doc() else DocIdSetIterator.NO_MORE_DOCS
  }

  def scoreAndNext(): Float = {
    val score = if (tp.freq() > 0) {
      tp.nextPosition()
      if (tp.isPayloadAvailable()) {
        curVec = tp.getPayload(curVec, 0)
        SemanticVector.similarity(vector, curVec) * weight
      } else {
        0.0f
      }
    } else {
      0.0f
    }

    if (tp.next()) {
      doc = tp.doc()
    } else {
      doc = DocIdSetIterator.NO_MORE_DOCS
    }

    score
  }
}

class SemanticVectorScorer(weight: SemanticVectorWeight, tps: List[DocAndVector]) extends Scorer(weight) {
  private[this] var curDoc = -1
  private[this] var svScore = 0.0f
  private[this] var scoredDoc = -1

  private[this] val pq = new PriorityQueue[DocAndVector] {
    super.initialize(tps.size)
    override def lessThan(nodeA: DocAndVector, nodeB: DocAndVector) = (nodeA.doc < nodeB.doc)
  }
  tps.foreach{ tp => pq.insertWithOverflow(tp) }

  override def score(): Float = {
    val doc = curDoc
    if (scoredDoc != doc) {
      var top = pq.top
      var sum = 0.0f
      while (top.doc == doc) {
        sum += top.scoreAndNext()
        top = pq.updateTop()
      }
      if (sum > 0.0f) svScore = sum else svScore = Float.MinPositiveValue
      scoredDoc = doc
    }
    svScore
  }

  override def docID(): Int = curDoc

  override def nextDoc(): Int = advance(0)

  override def advance(target: Int): Int = {
    var top = pq.top
    val doc = if (target <= curDoc && curDoc < DocIdSetIterator.NO_MORE_DOCS) curDoc + 1 else target
    while (top.doc < doc) {
      top.fetchDoc(doc)
      top = pq.updateTop()
    }
    curDoc = top.doc
    curDoc
  }
}

