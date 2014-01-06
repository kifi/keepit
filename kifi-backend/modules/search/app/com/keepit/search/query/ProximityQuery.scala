package com.keepit.search.query

import com.keepit.search.index.Searcher
import com.keepit.search.query.QueryUtil._
import com.keepit.search.util.AhoCorasick
import com.keepit.search.util.LocalAlignment
import com.keepit.search.util.LocalAlignment._
import org.apache.lucene.index.AtomicReaderContext
import org.apache.lucene.index.DocsAndPositionsEnum
import org.apache.lucene.index.IndexReader
import org.apache.lucene.index.Term
import org.apache.lucene.search.Query
import org.apache.lucene.search.Scorer
import org.apache.lucene.search.Weight
import org.apache.lucene.search.ComplexExplanation
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import org.apache.lucene.search.Explanation
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.similarities.Similarity
import org.apache.lucene.util.Bits
import org.apache.lucene.util.PriorityQueue
import org.apache.lucene.util.ToStringUtils
import java.lang.{Float => JFloat}
import java.util.{Set => JSet}
import scala.collection.JavaConversions._
import scala.collection.mutable.ArrayBuffer
import scala.math._
import com.keepit.common.logging.Logging

object ProximityQuery extends Logging {

  val maxLength = 64  // we use first 64 terms (enough?)

  def apply(terms: Seq[Seq[Term]], phrases: Set[(Int, Int)] = Set(), phraseBoost: Float = 0.0f, gapPenalty: Float, threshold: Float) = new ProximityQuery(terms, phrases, phraseBoost, gapPenalty, threshold)

  def buildPhraseDict(termIds: Array[Int], phrases: Set[(Int, Int)]): Seq[(Seq[Int], Match)] = {
    val posNotInPhrase = (0 until termIds.length).toArray
    phrases.foreach{ case (pos, len) =>
      if (pos + len <= posNotInPhrase.length) {
        (pos until (pos + len)).foreach{ idx => posNotInPhrase(idx) = -1 }
      }
    }

    val dict = (posNotInPhrase.filter{ _ >= 0 }.map{ pos => (Seq(termIds(pos)), TermMatch(pos).asInstanceOf[Match]) } ++
      phrases.map{ case (pos, len) => (termIds.slice(pos, pos + len).toSeq, (if (len == 1) TermMatch(pos) else PhraseMatch(pos, len)).asInstanceOf[Match]) })

    // verify
    dict.foreach{ case (p, m) => if (p.size != m.len) log.error(s"bad phrase: ($p, $m)") }

    dict
  }
}

class ProximityQuery(val terms: Seq[Seq[Term]], val phrases: Set[(Int, Int)] = Set(), val phraseBoost: Float, val gapPenalty: Float, val threshold: Float) extends Query {

  override def createWeight(searcher: IndexSearcher): Weight = new ProximityWeight(this)

  override def rewrite(reader: IndexReader): Query = this

  override def extractTerms(out: JSet[Term]): Unit = terms.foreach{ ts => out.addAll(ts) }

  override def toString(s: String) = {
    val termsString = terms.map{ t => if (t.size == 1) t.head.toString else t.mkString("(", ",", ")") }.mkString(",")
    s"proximity(${termsString})${ToStringUtils.boost(getBoost())}"
  }

  override def equals(obj: Any): Boolean = obj match {
    case prox: ProximityQuery => (terms == prox.terms && getBoost() == prox.getBoost())
    case _ => false
  }

  override def hashCode(): Int = terms.hashCode() + JFloat.floatToRawIntBits(getBoost())
}

class ProximityWeight(query: ProximityQuery) extends Weight {

  private[this] var value = 0.0f

  val gapPenalty = query.gapPenalty
  val threshold = query.threshold

  private[this] val termIdMap = {
    var id = -1
    query.terms.take(ProximityQuery.maxLength).foldLeft(Map.empty[Seq[Term], Int]){ (m, term) =>
      if (m.contains(term)) m
      else {
        id += 1
        m + (term -> id)
      }
    }
  }
  private[this] val termIds = query.terms.take(ProximityQuery.maxLength).map(termIdMap).toArray
  private[this] val phraseMatcher: Option[PhraseMatcher] = {
    if (query.phrases.isEmpty) None else Some(new PhraseMatcher(ProximityQuery.buildPhraseDict(termIds, query.phrases)))
  }

  private[this] val maxRawScore = LocalAlignment(termIds, phraseMatcher, query.phraseBoost, gapPenalty).maxScore

  def getCalibrationValue = value / maxRawScore

  override def scoresDocsOutOfOrder() = false

  override def getValueForNormalization() = {
    val boost = query.getBoost()
    boost * boost
  }

  override def normalize(norm: Float, topLevelBoost: Float) {
    value = query.getBoost * norm * topLevelBoost
  }

  override def explain(context: AtomicReaderContext, doc: Int) = {
    val sc = scorer(context, true, false, context.reader.getLiveDocs);
    val exists = (sc != null && sc.advance(doc) == doc);
    val termsString = query.terms.map{ t => if (t.size == 1) t.head.toString else t.mkString("(", ",", ")") }.mkString(",")
    val phrases = if (query.phrases.isEmpty) {
      ""
    } else {
      query.phrases.map{ case (pos, len) => query.terms.slice(pos , pos + len).map(_.head.text).mkString(" ") }.mkString("[", " ; ", "]")
    }

    val result = new ComplexExplanation()
    if (exists) {
      result.setDescription(s"proximity(${termsString + phrases}), product of:")
      val proxScore = sc.score
      result.setValue(proxScore)
      result.setMatch(true)
      result.addDetail(new Explanation(proxScore/value, s"proximity score. threshold = $threshold"))
      result.addDetail(new Explanation(value, "weight value"))
    } else {
      result.setDescription(s"proximity(${termsString + phrases}), doesn't match id ${doc}")
      result.setValue(0)
      result.setMatch(false)
    }
    result
  }

  def getQuery() = query

  override def scorer(context: AtomicReaderContext, scoreDocsInOrder: Boolean, topScorer: Boolean, acceptDocs: Bits): Scorer = {
    val buf = new ArrayBuffer[PositionAndId](termIdMap.size)
    termIdMap.foreach{ case (equivTerms, id) =>
      equivTerms.foreach{ term =>
        val enum = termPositionsEnum(context, term, acceptDocs)
        if (enum != null) buf += new PositionAndId(enum, term.text(), id)
      }
    }

    if (buf.isEmpty) null else new ProximityScorer(this, buf.toArray, termIds, phraseMatcher, query.phraseBoost, threshold)
  }
}


/**
 * Each term is associated with a PositionAndMask instance.
 * At any time,
 * doc = the current document associated with the term
 * pos = current position of the term in current document
 * posLeft = number of unvisited term positions in current document
 * mask : reflects the position of the term in query.
 */
private[query] final class PositionAndId(val tp: DocsAndPositionsEnum, val termText: String, val id: Int) {
  var doc = -1
  var pos = -1

  private[this] var posLeft = 0

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

class ProximityScorer(weight: ProximityWeight, tps: Array[PositionAndId], termIds: Array[Int], phraseMatcher: Option[PhraseMatcher], phraseBoost: Float, threshold: Float) extends Scorer(weight) with Logging {
  private[this] var curDoc = -1
  private[this] var proximityScore = 0.0f
  private[this] var scoredDoc = -1
  private[this] val weightVal = weight.getCalibrationValue

  private[this] val pq = new PriorityQueue[PositionAndId](tps.length) {
    override def lessThan(nodeA: PositionAndId, nodeB: PositionAndId) = {
      if (nodeA.doc == nodeB.doc) nodeA.pos < nodeB.pos
      else nodeA.doc < nodeB.doc
    }
  }
  tps.foreach{ tp => pq.insertWithOverflow(tp) }

  private[this] val localAlignment = LocalAlignment(termIds, phraseMatcher, phraseBoost, weight.gapPenalty)

  override def score(): Float = {
    // compute edit distance based proximity score
    val doc = curDoc
    if (scoredDoc != doc) {
      // start fetching position for all terms at this doc
      // in fact, it only fetches the first position (if exists) for each term
      var top = pq.top
      while (top.doc == doc && top.pos == -1) {
        top.nextPos()
        top = pq.updateTop()
      }

      // go through all positions
      localAlignment.begin()
      while (top.doc == doc && top.pos < Int.MaxValue) { // doc still have term positions left
        localAlignment.update(top.id, top.pos) // note: doc is fixed, earlier term position fetched first, the associated term also changes
        top.nextPos()
        top = pq.updateTop()
      }
      localAlignment.end()

      proximityScore = weightVal * localAlignment.score
      scoredDoc = doc
    }
    proximityScore
  }

  override def docID(): Int = curDoc

  override def nextDoc(): Int = {
    var docIter = advance(0)
    score()
    while(docIter < NO_MORE_DOCS && proximityScore < threshold) {
      log.info(s"proximity score for doc id ${docIter} is $proximityScore, threshold = $threshold, doc skipped")
      docIter = advance(0)
      score()
    }
    docIter
  }

  override def advance(target: Int): Int = {
    var top = pq.top
    val doc = if (target <= curDoc && curDoc < NO_MORE_DOCS) curDoc + 1 else target
    while (top.doc < doc) {
      top.fetchDoc(doc) // note: this modifies top.doc, need to reheapify.
      top = pq.updateTop()
    }
    curDoc = top.doc
    curDoc
  }

  override def freq(): Int = 1
}

