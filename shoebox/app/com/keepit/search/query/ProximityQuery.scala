package com.keepit.search.query

import com.keepit.search.index.Searcher
import org.apache.lucene.index.AtomicReaderContext
import org.apache.lucene.index.DocsAndPositionsEnum
import org.apache.lucene.index.IndexReader
import org.apache.lucene.index.Term
import com.keepit.search.query.QueryUtil._
import org.apache.lucene.search.Query
import org.apache.lucene.search.Scorer
import org.apache.lucene.search.Weight
import org.apache.lucene.search.ComplexExplanation
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import org.apache.lucene.search.Explanation
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.similarities.Similarity
import org.apache.lucene.util.PriorityQueue
import org.apache.lucene.util.ToStringUtils
import java.lang.{Float => JFloat}
import java.util.{Set => JSet}
import scala.collection.JavaConversions._
import scala.math._
import java.util.Arrays
import org.apache.lucene.util.Bits

object ProximityQuery {
  def apply(terms: Seq[Term]) = new ProximityQuery(terms)

  val gapPenalty = 0.05f
}

class ProximityQuery(val terms: Seq[Term]) extends Query {

  override def createWeight(searcher: IndexSearcher): Weight = new ProximityWeight(this)

  override def rewrite(reader: IndexReader): Query = this

  override def extractTerms(out: JSet[Term]): Unit = out.addAll(terms)

  override def toString(s: String) = "proximity(%s)%s".format(terms.mkString(","), ToStringUtils.boost(getBoost()))

  override def equals(obj: Any): Boolean = obj match {
    case prox: ProximityQuery => (terms == prox.terms && getBoost() == prox.getBoost())
    case _ => false
  }

  override def hashCode(): Int = terms.hashCode() + JFloat.floatToRawIntBits(getBoost())
}

class ProximityWeight(query: ProximityQuery) extends Weight {
  private[this] var value = 0.0f
  private[this] val maxRawScore = {
    val n = query.terms.size.toFloat
    // max possible proximity score
    ((n * (n + 1.0f) / 2.0f) - (ProximityQuery.gapPenalty * n * (n - 1.0f) / 2.0f))
  }

  def getWeightValue = value / maxRawScore

  override def scoresDocsOutOfOrder() = false

  override def getValueForNormalization() = {
    value = query.getBoost()
    value * value
  }

  override def normalize(norm: Float, topLevelBoost: Float) { value *= (norm * topLevelBoost) }

  override def explain(context: AtomicReaderContext, doc: Int) = {
    val sc = scorer(context, true, false, context.reader.getLiveDocs);
    val exists = (sc != null && sc.advance(doc) == doc);

    val result = new ComplexExplanation()
    if (exists) {
      result.setDescription("proximity(%s), product of:".format(query.terms.mkString(",")))
      val proxScore = sc.score
      result.setValue(proxScore)
      result.setMatch(true)
      result.addDetail(new Explanation(proxScore/value, "proximity score"))
      result.addDetail(new Explanation(value, "weight value"))
    } else {
      result.setDescription("proximity(%s), doesn't match id %d".format(query.terms.mkString(","), doc))
      result.setValue(0)
      result.setMatch(false)
    }
    result
  }

  def getQuery() = query

  override def scorer(context: AtomicReaderContext, scoreDocsInOrder: Boolean, topScorer: Boolean, acceptDocs: Bits): Scorer = {
    if (query.terms.size > 0) {
      var i = -1
      // uses first 64 terms (enough?)
      val tps = query.terms.take(64).foldLeft(Map.empty[String, PositionAndMask]){ (tps, term) =>
        i += 1
        val termText = term.text()
        tps + (termText -> (tps.getOrElse(termText, makePositionAndMask(context, term, acceptDocs)).setBit(i)))
      }
      new ProximityScorer(this, tps.values.toArray)
    } else {
      null
    }
  }

  private def makePositionAndMask(context: AtomicReaderContext, term: Term, acceptDocs: Bits) = {
    val enum = termPositionsEnum(context, term, acceptDocs)
    if (enum == null) {
      new PositionAndMask(EmptyDocsAndPositionsEnum, term.text())
    } else {
      new PositionAndMask(enum, term.text())
    }
  }
}

private[query] final class PositionAndMask(val tp: DocsAndPositionsEnum, val termText: String) {
  var doc = -1
  var pos = -1

  private[this] var mask: Long = 0L
  private[this] var posLeft = 0

  def getMask = mask
  def setBit(index: Int) = {
    mask = (mask | (1L << index))
    this
  }

  def fetchDoc(target: Int) {
    pos = -1
    doc = tp.advance(target)
    if (doc < NO_MORE_DOCS) {
      posLeft = tp.freq()
    } else {
      posLeft = 0
    }
  }

  def nextPos() {
    if (posLeft > 0) {
      pos = tp.nextPosition()
      posLeft -= 1
    }
    else {
      pos = Int.MaxValue
    }
  }
}

class ProximityScorer(weight: ProximityWeight, tps: Array[PositionAndMask]) extends Scorer(weight) {
  private[this] var curDoc = -1
  private[this] var proximityScore = 0.0f
  private[this] var scoredDoc = -1
  private[this] val numTerms = tps.length
  private[this] val rl = new Array[Float](numTerms + 1) // run lengths
  private[this] val ls = new Array[Float](numTerms + 1) // local scores
  private[this] val weightVal = weight.getWeightValue

  private[this] def gapPenalty(distance: Int) = ProximityQuery.gapPenalty * distance.toFloat

  private[this] val pq = new PriorityQueue[PositionAndMask](numTerms) {
    override def lessThan(nodeA: PositionAndMask, nodeB: PositionAndMask) = {
      if (nodeA.doc == nodeB.doc) nodeA.pos < nodeB.pos
      else nodeA.doc < nodeB.doc
    }
  }
  tps.foreach{ tp => pq.insertWithOverflow(tp)}

  override def score(): Float = {
    // compute edit distance based proximity score
    val insertCost = 1.0f
    val baseEditCost = 1.0f
    val doc = curDoc
    if (scoredDoc != doc) {
      var top = pq.top
      var maxScore = 0.0f
      if (top.pos < Int.MaxValue) {
        var i = 0
        Arrays.fill(rl, 0.0f) // clear the run lengths
        Arrays.fill(ls, 0.0f) // clear the local scores

        // start fetching position for all terms
        while (top.doc == doc && top.pos == -1) {
          top.nextPos()
          top = pq.updateTop()
        }

        // Find all partial sequence matches (possible phrases) using the dynamic programming.
        // This is similar to a local alignment matching in bioinformatics, but we disallow gaps.
        // We give a weight to each term occurrence using a local alignment score, thus, a term in a
        // a matching sequence gets a high weight. A weight is diffused to following positions with a constant decay.
        // A local score at a position is the sum of weights of all terms.
        // The max local score + the base score is the score of the current document.
        var prevPos = -1
        while (top.doc == doc && top.pos < Int.MaxValue) {
          val curPos = top.pos
          val mask = top.getMask
          var i = 1
          // update run lengths and local scores
          var prevRun = 0.0f
          var localScoreSum = 0.0f
          while (i <= numTerms) {
            val runLen = if (((1L << (i - 1)) & mask) != 0) prevRun + 1.0f else 0.0f
            val localScore = max(ls(i) - (gapPenalty(curPos - prevPos)), 0.0f)
            prevRun = rl(i) // save the run length of previous round
            rl(i) = runLen
            ls(i) = if (localScore < runLen) runLen else localScore
            localScoreSum += ls(i)
            i += 1
          }
          maxScore = max(maxScore, localScoreSum)
          top.nextPos()
          top = pq.updateTop()
          if (top.pos > curPos) prevPos = curPos
        }
        proximityScore = maxScore * weightVal
      }
      scoredDoc = doc
    }
    proximityScore
  }

  override def docID(): Int = curDoc

  override def nextDoc(): Int = advance(0)

  override def advance(target: Int): Int = {
    var top = pq.top
    val doc = if (target <= curDoc && curDoc < NO_MORE_DOCS) curDoc + 1 else target
    while (top.doc < doc) {
      top.fetchDoc(doc)
      top = pq.updateTop()
    }
    curDoc = top.doc
    curDoc
  }

  override def freq(): Int = 1
}

