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
import scala.math._

object ProximityQuery {
  def apply(terms: Seq[Term], phrases: Set[(Int, Int)] = Set(), phraseBoost: Float = 0.0f) = new ProximityQuery(terms, phrases, phraseBoost)

  def buildPhraseDict(termIds: Array[Int], phrases: Set[(Int, Int)]) = {
    val notInPhrase = termIds.clone
    phrases.foreach{ case (pos, len) =>
      (pos until (pos + len)).foreach{ idx => notInPhrase(idx) = -1 }
    }

    (notInPhrase.filter{ _ >= 0 }.map{ id => (Seq(id), TermMatch(1).asInstanceOf[Match]) } ++
      phrases.map{ case (pos, len) => (termIds.slice(pos, pos + len).toSeq, (if (len == 1) TermMatch(1) else PhraseMatch(pos, len)).asInstanceOf[Match]) })
  }

  val gapPenalty = 0.05f
}

class ProximityQuery(val terms: Seq[Term], val phrases: Set[(Int, Int)] = Set(), val phraseBoost: Float) extends Query {

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
  // we use first 64 terms (enough?)

  private[this] var value = 0.0f

  private[this] val termIdMap = {
    var id = -1
    query.terms.take(64).foldLeft(Map.empty[Term, Int]){ (m, term) =>
      if (m.contains(term)) m
      else {
        id += 1
        m + (term -> id)
      }
    }
  }
  private[this] val termIds = query.terms.take(64).map(termIdMap).toArray
  private[this] val phraseMatcher: Option[PhraseMatcher] = {
    if (query.phrases.isEmpty) None else Some(new PhraseMatcher(ProximityQuery.buildPhraseDict(termIds, query.phrases)))
  }

  private[this] val maxRawScore = LocalAlignment(termIds, phraseMatcher, query.phraseBoost, ProximityQuery.gapPenalty).maxScore

  def getWeightValue = value / maxRawScore

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

    val result = new ComplexExplanation()
    if (exists) {
      val phrasesOpt = if (query.phrases.isEmpty) {
        None
      } else {
        Some(query.phrases.map{ case (pos, len) => query.terms.slice(pos , pos + len).map(_.text).mkString(" ") }.mkString("[", " ; ", "]"))
      }

      result.setDescription("proximity(%s), product of:".format(query.terms.mkString(",") + phrasesOpt.getOrElse("")))
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
      val tps = termIdMap.map{ case (term, id) =>
        val termText = term.text()
        makePositionAndId(context, term, id, acceptDocs)
      }.toArray
      new ProximityScorer(this, tps, termIds, phraseMatcher, query.phraseBoost)
    } else {
      null
    }
  }

  private def makePositionAndId(context: AtomicReaderContext, term: Term, id: Int, acceptDocs: Bits) = {
    val enum = termPositionsEnum(context, term, acceptDocs)
    if (enum == null) {
      new PositionAndId(EmptyDocsAndPositionsEnum, term.text(), id)
    } else {
      new PositionAndId(enum, term.text(), id)
    }
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

class ProximityScorer(weight: ProximityWeight, tps: Array[PositionAndId], termIds: Array[Int], phraseMatcher: Option[PhraseMatcher], phraseBoost: Float) extends Scorer(weight) {
  private[this] var curDoc = -1
  private[this] var proximityScore = 0.0f
  private[this] var scoredDoc = -1
  private[this] val weightVal = weight.getWeightValue

  private[this] val pq = new PriorityQueue[PositionAndId](termIds.length) {
    override def lessThan(nodeA: PositionAndId, nodeB: PositionAndId) = {
      if (nodeA.doc == nodeB.doc) nodeA.pos < nodeB.pos
      else nodeA.doc < nodeB.doc
    }
  }
  tps.foreach{ tp => pq.insertWithOverflow(tp) }

  private[this] val localAlignment = LocalAlignment(termIds, phraseMatcher, phraseBoost, ProximityQuery.gapPenalty)

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

  override def nextDoc(): Int = advance(0)

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

