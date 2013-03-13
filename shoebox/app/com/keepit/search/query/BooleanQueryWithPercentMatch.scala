package com.keepit.search.query

import org.apache.lucene.index.IndexReader
import org.apache.lucene.search.{BooleanQuery => LBooleanQuery}
import org.apache.lucene.search.BooleanScorer2
import org.apache.lucene.search.Query
import org.apache.lucene.search.Searcher
import org.apache.lucene.search.Scorer
import org.apache.lucene.search.Similarity
import org.apache.lucene.search.Weight
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import org.apache.lucene.search.BooleanClause
import org.apache.lucene.util.PriorityQueue
import scala.collection.mutable.ArrayBuffer
import scala.collection.JavaConversions._
import scala.math._
import java.util.{ArrayList, List => JList}
import com.keepit.common.logging.Logging

object BooleanQueryWithPercentMatch {
  def apply(clauses: JList[BooleanClause], percentMatch: Float, disableCoord: Boolean) = {
    val query = new BooleanQueryWithPercentMatch(disableCoord)
    query.setPercentMatch(percentMatch)
    clauses.foreach{ clause => query.add(clause) }
    query
  }
}

class BooleanQueryWithPercentMatch(val disableCoord: Boolean = false) extends LBooleanQuery(disableCoord) {

  private[this] var percentMatch = 0.0f

  def setPercentMatch(pctMatch: Float) { percentMatch = pctMatch }
  def getPercentMatch() = percentMatch

  override def rewrite(reader: IndexReader) = {
    if (clauses.size() == 1) { // optimize 1-clause queries
      val c = clauses.get(0)
      if (!c.isProhibited()) {
        var query = c.getQuery().rewrite(reader)
        if (getBoost() != 1.0f) {
           // if rewrite was no-op then clone before boost
          if (query eq c.getQuery()) query = query.clone().asInstanceOf[Query]
          query.setBoost(getBoost() * query.getBoost())
        }
        query
      }
    }

    var returnQuery = this
    val rewrittenQuery = new BooleanQueryWithPercentMatch(disableCoord) // recursively rewrite
    rewrittenQuery.setPercentMatch(percentMatch)
    getClauses.foreach { c =>
      val query = c.getQuery.rewrite(reader)

      if (query eq c.getQuery) rewrittenQuery.add(c)
      else {
        rewrittenQuery.add(query, c.getOccur)
        returnQuery = rewrittenQuery
      }
    }
    returnQuery
  }

  override def clone(): Object = {
    val clone = new BooleanQueryWithPercentMatch(disableCoord)
    clone.setPercentMatch(percentMatch)
    clauses.foreach{ c => clone.add(c) }
    clone.setBoost(getBoost)
    clone.setMinimumNumberShouldMatch(getMinimumNumberShouldMatch)
    clone
  }

  override def createWeight(searcher: Searcher) = {

    new BooleanWeight(searcher, disableCoord) {
      override def scorer(reader: IndexReader, scoreDocsInOrder: Boolean, topScorer: Boolean): Scorer = {
        val required = new ArrayBuffer[Scorer]
        val prohibited = new ArrayBuffer[Scorer]
        val optional = new ArrayBuffer[(Scorer, Float)]

        var totalValueOnRequired = 0.0f
        var totalValueOnOptional = 0.0f
        clauses.zip(weights).foreach{ case (c, w) =>
          val subScorer = w.scorer(reader, true, false)
          if (c.isRequired()) {
            totalValueOnRequired += w.getValue
            // if a required clasuse does not have a scorer, no hit
            if (subScorer == null) return null
            required += subScorer
          } else if (c.isProhibited()) {
            if (subScorer != null) prohibited += subScorer
          } else {
            totalValueOnOptional += w.getValue
            if (subScorer != null) {
              optional += ((subScorer, w.getValue))
            }
          }
        }

        if (required.isEmpty && optional.isEmpty) {
          // no required and optional clauses.
          null
        } else {
          val threshold = (totalValueOnRequired + totalValueOnOptional) * percentMatch / 100.0f - totalValueOnRequired
          BooleanScorer(this, disableCoord, similarity, maxCoord, threshold,
                        required.toArray, totalValueOnRequired, optional.toArray, totalValueOnOptional, prohibited.toArray)
        }
      }
    }
  }

  override def equals(obj: Any): Boolean = {
    obj match {
      case other: BooleanQueryWithPercentMatch =>
        (percentMatch == other.getPercentMatch &&
         getBoost == other.getBoost &&
         clauses.equals(other.clauses) &&
         getMinimumNumberShouldMatch == other.getMinimumNumberShouldMatch &&
         disableCoord == other.disableCoord)
      case _ => false
    }
  }

  override def toString(s: String) = "%s>%f".format(super.toString(s), percentMatch)
}

object BooleanScorer {
  def apply(weight: Weight, disableCoord: Boolean, similarity: Similarity, maxCoord: Int, threshold: Float,
            required: Array[Scorer], requiredValue: Float,
            optional: Array[(Scorer, Float)], optionalValue: Float,
            prohibited: Array[Scorer]) = {
    def conjunction() = {
      val coord = if (disableCoord) 1.0f else similarity.coord(required.length, required.length)
      new BooleanAndScorer(weight, coord, required, requiredValue)
    }
    def disjunction() = {
      val maxCoord = required.length + optional.length
      val coordFactors = (0 to optional.length).map{ i => if (disableCoord) 1.0f else similarity.coord(i + required.length, maxCoord) }.toArray
      new BooleanOrScorer(weight, optional, coordFactors, threshold, optionalValue)
    }
    def prohibit(source: Scorer with Coordinator) = {
      new BooleanNotScorer(weight, source, prohibited)
    }

    val mainScorer =
      if (required.length > 0 && optional.length > 0) {
        new BooleanScorer(weight, conjunction(), disjunction(), threshold, requiredValue + optionalValue)
      } else if (required.length > 0){
        conjunction()
      } else if (optional.length > 0) {
        disjunction()
      } else QueryUtil.emptyScorer(weight)

    if (prohibited.length > 0) prohibit(mainScorer) else mainScorer
  }
}

class BooleanScorer(weight: Weight, required: BooleanAndScorer, optional: BooleanOrScorer, threshold: Float, val value: Float) extends Scorer(weight) with Coordinator {

  private[this] var doc = -1
  private[this] var scoredDoc = -1
  private[this] var scoreValue = 0.0f

  override def docID() = doc

  override def score(): Float = {
    if (scoredDoc != doc) {
      scoreValue = required.score()
      if (optional.docID == doc) scoreValue += optional.score()
      scoredDoc = doc
    }
    scoreValue
  }

  override def nextDoc(): Int = advance(0)

  override def advance(target: Int): Int = {
    doc = required.advance(target)
    if (threshold > 0.0f) { // some of the optional clauses must match to reach the threshold.
      while (doc < NO_MORE_DOCS && optional.advance(doc) != doc) {
        doc = required.advance(optional.docID)
      }
    } else { // the required clauses have enough weights. the optional clause is truly optional.
      if (doc < NO_MORE_DOCS && optional.docID < doc) {
        optional.advance(doc)
      }
    }
    doc
  }

  override def coord = (required.value + optional.value)/value
}

class BooleanAndScorer(weight: Weight, val coordFactor: Float, scorers: Array[Scorer], val value: Float) extends Scorer(weight) with Coordinator {

  private[this] var doc = -1
  private[this] var scoredDoc = -1
  private[this] var scoreValue = 0.0f

  override def docID() = doc

  override def score(): Float = {
    if (doc != scoredDoc) {
      scoredDoc = doc
      var sum = 0.0f
      var i = 0
      while (i < scorers.length) {
        sum += scorers(i).score()
        i += 1
      }
      scoreValue = sum * coordFactor
    }
    scoreValue
  }

  override def nextDoc(): Int = advance(0)

  override def advance(target: Int): Int = {
    if (doc < NO_MORE_DOCS) {
      doc = if (target <= doc) doc + 1 else target
    }

    var i = 0
    while (doc < NO_MORE_DOCS && i < scorers.length) {
      val sc = scorers(i)
      var scdoc = sc.docID()
      if (scdoc < doc) scdoc = sc.advance(doc)
      if (scdoc == doc) {
        i += 1
      } else {
        doc = scdoc
        i = 0
      }
    }
    doc
  }

  override def coord = 1.0f
}

class BooleanOrScorer(weight: Weight, scorers: Array[(Scorer, Float)], coordFactors: Array[Float], threshold: Float, maxOverlapValue: Float)
extends Scorer(weight) with Coordinator with Logging {

  private[this] var doc = -1
  private[this] var scoreValue = 0.0f
  private[this] var overlapValue = 0.0f
  private[this] val overlapValueUnit = maxOverlapValue / scorers.length

  private[this] class ScorerDoc(val scorer: Scorer, val value: Float, var doc: Int, var scoredDoc: Int) {
    private[this] var scoreVal = 0.0f

    def score = {
      if (doc != scoredDoc) {
        scoreVal = scorer.score()
        scoredDoc = doc
      } else {
        log.info("score method called twice [docid=%d]".format(doc))
      }
      scoreVal
    }
  }

  private[this] val pq = new PriorityQueue[ScorerDoc] {
    super.initialize(scorers.length)
    override def lessThan(a: ScorerDoc, b: ScorerDoc) = (a.doc < b.doc)
  }

  scorers.foreach{ case (s, v) => pq.insertWithOverflow(new ScorerDoc(s, v, -1, -1)) }

  override def docID() = doc

  override def score(): Float = scoreValue

  private def doScore(): Float = {
    var matchValue = 0.0f
    var sum = 0.0f
    var cnt = 0
    if (doc < NO_MORE_DOCS) {
      var top = pq.top
      while (top.doc == doc) {
        sum += top.score
        matchValue += top.value
        cnt += 1
        top.doc = top.scorer.nextDoc()
        top = pq.updateTop()
      }
    }
    overlapValue = matchValue
    if (matchValue < threshold) 0.0f else sum * coordFactors(cnt)
  }

  override def nextDoc(): Int = advance(0)

  override def advance(target: Int): Int = {
    if (doc < NO_MORE_DOCS) {
      doc = if (target <= doc) doc + 1 else target
      scoreValue = 0.0f
      var top = pq.top
      while (top.doc < doc) {
        top.doc = top.scorer.advance(doc)
        top = pq.updateTop()
      }
      doc = top.doc
      while (scoreValue <= 0.0f && doc < NO_MORE_DOCS) {
        doc = top.doc
        scoreValue = doScore() // doScore advances underlying scorers
        top = pq.top
      }
    }
    doc
  }

  def value = overlapValue
  override def coord = overlapValueUnit / (overlapValueUnit + (maxOverlapValue - overlapValue))
}

class BooleanNotScorer(weight: Weight, scorer: Scorer with Coordinator, prohibited: Array[Scorer]) extends Scorer(weight) with Coordinator {

  private[this] var doc = -1
  private[this] var scoredDoc = -1
  private[this] var scoreValue = 0.0f

  override def docID() = doc

  override def score(): Float = {
    if (doc != scoredDoc) {
      scoredDoc = doc
      scoreValue = scorer.score()
    }
    scoreValue
  }

  override def nextDoc(): Int = advance(0)

  override def advance(target: Int): Int = {
    doc = scorer.advance(target)
    while (doc < NO_MORE_DOCS && isProhibited) {
      doc = scorer.advance(0)
    }
    doc
  }

  override def coord = scorer.coord

  private def isProhibited = {
    prohibited.exists{ n =>
      if (n.docID < doc) n.advance(doc)
      (n.docID == doc)
    }
  }
}
