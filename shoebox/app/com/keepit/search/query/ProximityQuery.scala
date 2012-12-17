package com.keepit.search.query

import org.apache.lucene.index.IndexReader
import org.apache.lucene.index.Term
import org.apache.lucene.index.TermPositions
import org.apache.lucene.search.Query
import org.apache.lucene.search.Scorer
import org.apache.lucene.search.Weight
import org.apache.lucene.search.Searcher
import org.apache.lucene.search.ComplexExplanation
import org.apache.lucene.search.Explanation
import org.apache.lucene.search.Similarity
import org.apache.lucene.search.DocIdSetIterator
import org.apache.lucene.util.PriorityQueue
import org.apache.lucene.util.ToStringUtils
import java.lang.{Float => JFloat}
import java.util.{Set => JSet}
import scala.collection.JavaConversions._
import scala.math._
import java.util.Arrays

object ProximityQuery {
  def apply(terms: Seq[Term]) = new ProximityQuery(terms)
}

class ProximityQuery(val terms: Seq[Term]) extends Query {

  override def createWeight(searcher: Searcher): Weight = {
    val similarity = searcher.getSimilarity
    val numDocs = searcher.maxDoc()
    new ProximityWeight(this)
  }

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
      var i = -1
      // uses first 64 terms (enough?)
      val tps = query.terms.take(64).foldLeft(Map.empty[String, PositionAndMask]){ (tps, term) =>
        i += 1
        val termText = term.text()
        tps + (termText -> (tps.getOrElse(termText, new PositionAndMask(reader.termPositions(term), termText).setBit(i))))
      }
      new ProximityScorer(this, tps.values.toArray)
    } else {
      QueryUtil.emptyScorer(this)
    }
  }
}

class PositionAndMask(val tp: TermPositions, val termText: String) {
  var doc = -1
  var pos = -1

  private[this] var mask: Long = 0L
  private[this] var posLeft = 0

  def getMask = mask
  def setBit(index: Int) = {
    mask = (mask | (1L << index))
    this
  }

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

class ProximityScorer(weight: ProximityWeight, tps: Array[PositionAndMask]) extends Scorer(weight) {
  private[this] var curDoc = -1
  private[this] var proximityScore = 0.0f
  private[this] var scoredDoc = -1
  private[this] val numTerms = tps.length
  private[this] val termPresenceScore = 1.0f // base score per term presence
  private[this] def gapPenalty(distance: Int) = 0.05f * distance.toFloat // gap penalty
  private[this] val rl = new Array[Float](numTerms + 1) // run lengths
  private[this] val ls = new Array[Float](numTerms + 1) // local scores

  private[this] val pq = new PriorityQueue[PositionAndMask] {
    super.initialize(numTerms)

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

    var top = pq.top
    var doc = top.doc
    if (scoredDoc != doc) {
      proximityScore = 0.0f
      var maxScore = 0.0f
      if (top.pos < Int.MaxValue) {
        var i = 0
        Arrays.fill(rl, 0.0f) // clear the run lengths
        Arrays.fill(ls, 0.0f) // clear the local scores

        // start fetching position for all terms, and cumulate term presence scores as the base score
        while (top.doc == doc && top.pos == -1) {
          proximityScore += termPresenceScore
          val pos = top.nextPos()
          top = pq.updateTop()
        }

        var prevPos = -1
        var prevRun = 0.0f
        // Find all partial sequence matches (possible phrases) using the dynamic programming.
        // This is similar to a local alignment matching in bioinformatics, but we disallow gaps.
        // We give a weight to each term occurrence using a local alignment score, thus, a term in a
        // a matching sequence gets a high weight. A weight is diffused to following positions with a constant decay.
        // A local score at a position is the sum of weights of all terms.
        // The max local score + the base score is the score of the current document.
        while (top.doc == doc && top.pos < Int.MaxValue) {
          val curPos = top.pos
          val mask = top.getMask
          var i = 1
          // update run lengths and local scores
          var localScoreSum = 0.0f
          while (i <= numTerms) {
            val runLen = if (((1L << (i - 1)) & mask) != 0) prevRun + 1.0f else 0.0f
            val localScore = max(ls(i) - (gapPenalty(curPos - prevPos)), 0.0f)
            prevRun = rl(i)
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
        proximityScore += maxScore
      }
      scoredDoc = doc
    }
    (sqrt(proximityScore.toDouble + 1.0d) - 1.0d).toFloat
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

