package com.keepit.search.query

import com.keepit.common.logging.Logging
import org.apache.lucene.index.IndexReader
import org.apache.lucene.index.Term
import org.apache.lucene.index.TermPositions
import org.apache.lucene.search.Query
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.PhraseQuery
import org.apache.lucene.search.TermQuery
import org.apache.lucene.search.Scorer
import org.apache.lucene.search.Weight
import org.apache.lucene.search.Searcher
import org.apache.lucene.search.ComplexExplanation
import org.apache.lucene.search.Explanation
import org.apache.lucene.search.Similarity
import org.apache.lucene.search.DocIdSetIterator
import org.apache.lucene.util.PriorityQueue
import org.apache.lucene.util.ToStringUtils
import java.util.{Set => JSet, HashSet => JHashSet}
import scala.collection.JavaConversions._
import scala.math._

object ProximityQuery extends Logging {

  def apply(fieldName: String, query: Query): ProximityQuery = apply(getTerms(fieldName, query))
  def apply(terms: Set[Term]): ProximityQuery = new ProximityQuery(terms)

  def getTerms(fieldName: String, query: Query): Set[Term] = {
    getTerms(query).filter{ _.field() == fieldName }
  }

  def getTerms(query: Query): Set[Term] = {
    query match {
      case q: TermQuery => fromTermQuery(q)
      case q: PhraseQuery => fromPhraseQuery(q)
      case q: BooleanQuery => fromBooleanQuery(q)
      case q: Query => fromOtherQuery(q)
      case null => Set.empty[Term]
    }
  }

  private def fromTermQuery(query: TermQuery) = Set(query.getTerm)
  private def fromPhraseQuery(query: PhraseQuery) = query.getTerms().toSet
  private def fromBooleanQuery(query: BooleanQuery) = {
    query.getClauses.map{ cl => if (!cl.isProhibited) getTerms(cl.getQuery) else Set.empty[Term] }.reduce{ _ union _ }
  }
  private def fromOtherQuery(query: Query) = {
    try {
      val terms = new JHashSet[Term]()
      query.extractTerms(terms)
      terms.toSet
    } catch {
      case _ =>
        log.warn("term extraction failed: %s".format(query.getClass.toString))
        Set.empty[Term]
    }
  }

  val scoreFactorHalfDecay = 4
  val scoreFactorTable = {
    val arr = new Array[Float](500)
    for (i <- 1 until arr.length) {
      arr(i) = 1.0f/(1.0f + (i.toFloat/scoreFactorHalfDecay))
    }
    arr
  }
  def scoreFactor(distance: Int) = {
    if (distance < scoreFactorTable.length) scoreFactorTable(distance) else 0.0f
  }
}

class ProximityQuery(val terms: Set[Term]) extends Query {

  override def createWeight(searcher: Searcher): Weight = new ProximityWeight(this, searcher.getSimilarity)

  override def rewrite(reader: IndexReader): Query = this

  override def extractTerms(out: java.util.Set[Term]): Unit = out.addAll(terms)

  override def toString(s: String) = {
    val buffer = new StringBuilder()
    buffer.append("proximity(")
    buffer.append(terms.mkString(","))
    buffer.append(")")
    buffer.append(ToStringUtils.boost(getBoost()));
    buffer.toString
  }

  override def equals(obj: Any): Boolean = obj match {
    case prox: ProximityQuery => (terms == prox.terms && getBoost() == prox.getBoost())
    case _ => false
  }

  override def hashCode(): Int = terms.hashCode() + java.lang.Float.floatToRawIntBits(getBoost())
}

class ProximityWeight(query: ProximityQuery, similarity: Similarity) extends Weight {
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
      result.setDescription("proximity(%), product of:".format(query.terms.mkString(",")))
      val proxScore = sc.score
      val boost = query.getBoost
      result.setValue(proxScore * boost)
      result.setMatch(true)
      result.addDetail(new Explanation(proxScore, "proximity score"))
      result.addDetail(new Explanation(boost, "boost"))
    } else {
      result.setDescription("proximity(%), doesn't match id %d".format(query.terms.mkString(","), doc))
      result.setValue(0)
      result.setMatch(false)
    }
    result
  }

  def getQuery() = query

  override def scorer(reader: IndexReader, scoreDocsInOrder: Boolean, topScorer: Boolean): Scorer = {
    if (query.terms.size > 1) {
      val tps = query.terms.map{
        term => new PositionAndWeight(reader.termPositions(term), similarity.idf(reader.docFreq(term), reader.numDocs) * value)
      }
      new ProximityScorer(this, tps)
    } else {
      new EmptyProximityScorer(this)
    }
  }
}

class PositionAndWeight(val tp: TermPositions, val weight: Float) {
  var doc = -1
  var pos = -1
  var posLeft = 0

  def fetchDoc(target: Int): Int = {
    pos = -1
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
    pos = -1
    if (tp.next()) {
      doc = tp.doc()
      posLeft = tp.freq()
    } else {
      doc = DocIdSetIterator.NO_MORE_DOCS
      posLeft = 0
    }
    doc
  }

  def nextPos(): Int = {
    if (posLeft > 0) {
      pos = tp.nextPosition()
      posLeft -= 1
    }
    else {
      pos = Int.MaxValue
    }
    pos
  }
}

class ProximityScorer(weight: ProximityWeight, tps: Set[PositionAndWeight]) extends Scorer(weight) {
  var proximityScore = 0.0f
  var scoredDoc = -1

  val pq = new PriorityQueue[PositionAndWeight] {
    super.initialize(tps.size)

    override def lessThan(nodeA: PositionAndWeight, nodeB: PositionAndWeight) = {
      if (nodeA.doc == nodeB.doc) {
        if (nodeA.pos == nodeB.pos) {
          nodeA.weight > nodeB.weight
        } else {
          nodeA.pos < nodeB.pos
        }
      }
      else {
        nodeA.doc < nodeB.doc
      }
    }
  }
  tps.foreach{ tp => pq.insertWithOverflow(tp)}

  override def score(): Float = {
    var top = pq.top
    var doc = top.doc
    if (scoredDoc != doc) {
      var sum = 0.0f
      if (top.pos < Int.MaxValue) {
        // start fetching position for all terms
        while (top.doc == doc && top.pos == -1) {
          top.nextPos()
          top = pq.updateTop()
        }

        var prev = top
        var prevPos = top.pos
        var prevWeight = top.weight
        while (top.doc == doc && top.pos < Int.MaxValue) {
          top.nextPos()
          top = pq.updateTop()
          if (prev eq top) {
            prev = top
            prevPos = top.pos
            prevWeight = top.weight
          } else {
            if (prevPos < top.pos) {
              sum += (prevWeight + top.weight) * ProximityQuery.scoreFactor(top.pos - prevPos)
              prevPos = top.pos
              prevWeight = top.weight
            }
          }
        }
      }
      proximityScore = (sqrt(sum.toDouble + 1.0d) - 1.0d).toFloat
      scoredDoc = doc
    }
    proximityScore
  }

  override def docID(): Int = pq.top.doc

  override def nextDoc(): Int = advance(0)

  override def advance(target: Int): Int = {
    var top = pq.top
    val doc = if (target <= top.doc && top.doc < DocIdSetIterator.NO_MORE_DOCS) top.doc + 1 else target
    while (top.doc < doc) {
      top.fetchDoc(target)
      top = pq.updateTop()
    }
    top.doc
  }
}

class EmptyProximityScorer(weight: ProximityWeight) extends Scorer(weight) {
  override def score() = 0.0f
  override def docID() = DocIdSetIterator.NO_MORE_DOCS
  override def nextDoc()= DocIdSetIterator.NO_MORE_DOCS
  override def advance(target: Int) = DocIdSetIterator.NO_MORE_DOCS
}
