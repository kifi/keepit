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

class SemanticVectorQuery(val terms: Set[Term]) extends Query {

  override def createWeight(searcher: LuceneSearcher): Weight = new SemanticVectorWeight(this, searcher.asInstanceOf[Searcher])

  override def rewrite(reader: IndexReader): Query = this

  override def extractTerms(out: JSet[Term]): Unit = out.addAll(terms)

  override def toString(s: String) = {
    val buffer = new StringBuilder()
    buffer.append("semantic vector (")
    buffer.append(terms.mkString(","))
    buffer.append(")")
    buffer.append(ToStringUtils.boost(getBoost()))
    buffer.toString
  }

  override def equals(obj: Any): Boolean = obj match {
    case svq: SemanticVectorQuery => (terms == svq.terms && getBoost() == svq.getBoost())
    case _ => false
  }

  override def hashCode(): Int = terms.hashCode() + JFloat.floatToRawIntBits(getBoost())
}

class SemanticVectorWeight(query: SemanticVectorQuery, searcher: Searcher) extends Weight {
  var value = 0.0f

  override def getValue() = value
  override def scoresDocsOutOfOrder() = false

  override def sumOfSquaredWeights() = {
    value = query.getBoost()
    value * value
  }

  override def normalize(norm: Float) { value *= norm }

  override def explain(reader: IndexReader, doc: Int) = {
    val sc = scorer(reader, true, false);
    val exists = (sc != null && sc.advance(doc) == doc);

    val result = new ComplexExplanation()
    if (exists) {
      result.setDescription("semantic vector (%), product of:".format(query.terms.mkString(",")))
      val svScore = sc.score
      val boost = query.getBoost
      result.setValue(svScore * boost)
      result.setMatch(true)
      result.addDetail(new Explanation(svScore, "semantic vector score"))
      result.addDetail(new Explanation(boost, "boost"))
    } else {
      result.setDescription("semantic vector (%), doesn't match id %d".format(query.terms.mkString(","), doc))
      result.setValue(0)
      result.setMatch(false)
    }
    result
  }

  def getQuery() = query

  override def scorer(reader: IndexReader, scoreDocsInOrder: Boolean, topScorer: Boolean): Scorer = {
    if (query.terms.size > 0) {
      val similarity = searcher.getSimilarity
      val numDocs = searcher.maxDoc()
      val tps = query.terms.map{
        term => new DocAndVector(reader.termPositions(term), searcher.getSemanticVector(term) , similarity.idf(searcher.docFreq(term), numDocs) * value)
      }
      new SemanticVectorScorer(this, tps)
    } else {
      QueryUtil.emptyScorer(this)
    }
  }
}

class DocAndVector(val tp: TermPositions, vector: Array[Byte], val weight: Float) {
  var doc = -1
  var posLeft = 0

  private var curVec = new Array[Byte](SemanticVector.arraySize)
  var distance = 0.0f

  def fetchDoc(target: Int): Int = {
    if (tp.skipTo(target)) {
      doc = tp.doc()
      posLeft = tp.freq()
    } else {
      doc = DocIdSetIterator.NO_MORE_DOCS
      posLeft = 0
    }
    doc
  }

  def nextDoc(): Int = {
    if (tp.next()) {
      doc = tp.doc()
      posLeft = tp.freq()
    } else {
      doc = DocIdSetIterator.NO_MORE_DOCS
      posLeft = 0
    }
    doc
  }

  def getDistance(): Float = {
    if (posLeft > 0) {
      tp.nextPosition()
      posLeft -= 1
      if (tp.isPayloadAvailable()) {
        curVec = tp.getPayload(curVec, 0)
      }
      SemanticVector.distance(vector, curVec).toFloat / SemanticVector.vectorSize
    } else {
      1.0f
    }
  }
}

class SemanticVectorScorer(weight: SemanticVectorWeight, tps: Set[DocAndVector]) extends Scorer(weight) {
  private var curDoc = -1
  private var svScore = 0.0f
  private var scoredDoc = -1

  val pq = new PriorityQueue[DocAndVector] {
    super.initialize(tps.size)
    override def lessThan(nodeA: DocAndVector, nodeB: DocAndVector) = (nodeA.doc < nodeB.doc)
  }
  tps.foreach{ tp => pq.insertWithOverflow(tp) }

  override def score(): Float = {
    var top = pq.top
    var doc = top.doc
    if (scoredDoc != doc) {
      var sum = 0.0f
      while (top.doc == doc) {
        sum += (top.weight * (1.0f - top.getDistance()))
        top.nextDoc()
        top = pq.updateTop()
      }
      svScore = (sqrt(sum.toDouble + 1.0d) - 1.0d).toFloat
      scoredDoc = doc
    }
    svScore
  }

  override def docID(): Int = pq.top.doc

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

