package com.keepit.search.query

import org.apache.lucene.index.IndexReader
import org.apache.lucene.search.{BooleanQuery => LBooleanQuery}
import org.apache.lucene.search.BooleanScorer2
import org.apache.lucene.search.Query
import org.apache.lucene.search.Searcher
import org.apache.lucene.search.Scorer
import org.apache.lucene.search.Similarity
import org.apache.lucene.search.Weight
import org.apache.lucene.search.DocIdSetIterator
import org.apache.lucene.search.BooleanClause
import org.apache.lucene.util.PriorityQueue
import scala.collection.mutable.ArrayBuffer
import scala.collection.JavaConversions._
import scala.math._
import java.util.{ArrayList, List => JList}

object BooleanQueryWithPercentMatch {
  def apply(clauses: JList[BooleanClause], percentMatch: Float, disableCoord: Boolean) = {
    val query = new BooleanQueryWithPercentMatch(disableCoord)
    query.setPercentMatch(percentMatch)
    clauses.foreach{ clause => query.add(clause) }
    query
  }
}

class BooleanQueryWithPercentMatch(val disableCoord: Boolean = false) extends LBooleanQuery(disableCoord) {

  private var percentMatch = 0.0f

  def setPercentMatch(pctMatch: Float) { percentMatch = pctMatch }
  def getPercentMatch() = percentMatch

  override def rewrite(reader: IndexReader) = {
    if (minNrShouldMatch == 0 && clauses.size() == 1) { // optimize 1-clause queries
      val c = clauses.get(0)
      if (!c.isProhibited()) {
        var query = c.getQuery().rewrite(reader)
        if (getBoost() != 1.0f) {
           // if rewrite was no-op then clone before boost
          if (query eq c.getQuery()) query = query.clone().asInstanceOf[Query]
          query.setBoost(getBoost() * query.getBoost());
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
      override def scorer(reader: IndexReader, scoreDocsInOrder: Boolean, topScorer: Boolean) = {
        val required = new ArrayBuffer[Scorer]
        val prohibited = new ArrayBuffer[Scorer]
        val optional = new ArrayBuffer[Scorer]

        var totalValueOnRequired = 0.0f
        var valuesOnOptional = new ArrayBuffer[Float]
        val success = clauses.zip(weights).forall{ case (c, w) =>
          val subScorer = w.scorer(reader, true, false)
          if (subScorer == null) c.isRequired()
          else {
            if (c.isRequired()) {
              required += subScorer
              totalValueOnRequired += w.getValue
            }
            else if (c.isProhibited()){
              prohibited += subScorer
            }
            else {
              optional += subScorer
              valuesOnOptional += w.getValue
            }
            true
          }
        }

        if (required.isEmpty && optional.isEmpty) {
          // no required and optional clauses.
          null
        } else if (optional.size() < minNrShouldMatch) {
          // either >1 req scorer, or there are 0 req scorers and at least 1
          // optional scorer. Therefore if there are not enough optional scorers
          // no documents will be matched by the query
          null
        }

        val threshold = (totalValueOnRequired + valuesOnOptional.foldLeft(0.0f){ (s, v) => s + v }) * percentMatch / 100.0f - totalValueOnRequired
        BooleanScorer(this, disableCoord, similarity, minNrShouldMatch, required.toArray, prohibited.toArray, optional.toArray, maxCoord,
                      valuesOnOptional.toArray, threshold)
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
}

object BooleanScorer {
  def apply(weight: Weight, disableCoord: Boolean, similarity: Similarity, minNrShouldMatch: Int,
            required: Array[Scorer], prohibited: Array[Scorer], optional: Array[Scorer], maxCoord: Int,
            valuesOnOptional: Array[Float], threshold: Float) = {
    def conjunction() = {
      val coord = if (disableCoord) 1.0f else similarity.coord(required.length, required.length)
      new BooleanAndScorer(weight, coord, required)
    }
    def disjunction() = {
      val maxCoord = required.length + optional.length
      val coordFactors = (0 to optional.length).map{ i => if (disableCoord) 1.0f else similarity.coord(i + required.length, maxCoord) }.toArray
      new BooleanOrScorer(weight, optional, coordFactors, valuesOnOptional, threshold)
    }
    def prohibit(source: Scorer) = {
      new BooleanNotScorer(weight, source, prohibited)
    }

    val mainScorer =
      if (required.length > 0 && optional.length > 0) {
        new BooleanScorer(weight, conjunction(), disjunction())
      } else if (required.length > 0){
        conjunction()
      } else if (optional.length > 0) {
        disjunction()
      } else QueryUtil.emptyScorer(weight)

    if (prohibited.length > 0) prohibit(mainScorer) else mainScorer
  }
}

class BooleanScorer(weight: Weight, required: BooleanAndScorer, optional: BooleanOrScorer) extends Scorer(weight) {

  private var doc = -1
  private var scoredDoc = -1
  private var scoreValue = 0.0f

  override def docID() = doc

  override def score(): Float = {
    if (scoredDoc != doc) {
      scoreValue = required.score
      if (optional.docID == doc) scoreValue += optional.score
      scoredDoc = doc
    }
    scoreValue
  }

  override def nextDoc(): Int = advance(0)

  override def advance(target: Int): Int = {
    doc = required.advance(target)
    if (doc < DocIdSetIterator.NO_MORE_DOCS) optional.advance(doc)
    doc
  }
}

class BooleanAndScorer(weight: Weight, coord: Float, scorers: Array[Scorer]) extends Scorer(weight) {

  private var doc = -1
  private var scoredDoc = -1
  private var scoreValue = 0.0f

  override def docID() = doc

  override def score(): Float = {
    if (doc > scoredDoc) {
      var sum = 0.0f
      var i = 0
      while (i < scorers.length) {
        sum += scorers(i).score()
        i += 1
      }
      scoreValue = sum * coord
    }
    scoredDoc = doc
    scoreValue
  }

  override def nextDoc(): Int = advance(0)

  override def advance(target: Int): Int = {
    if (doc < DocIdSetIterator.NO_MORE_DOCS) {
      doc = if (target <= doc) doc + 1 else target
    }

    var i = 0
    while (doc < DocIdSetIterator.NO_MORE_DOCS && i < scorers.length) {
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
}

class BooleanOrScorer(weight: Weight, scorers: Array[Scorer], coordFactors: Array[Float], values: Array[Float], threshold: Float) extends Scorer(weight) {

  private var doc = -1
  private var scoreValue = 0.0f

  class ScorerDoc(val scorer: Scorer, var doc: Int, val value: Float)

  private val pq = new PriorityQueue[ScorerDoc] {
    super.initialize(scorers.length)
    override def lessThan(a: ScorerDoc, b: ScorerDoc) = (a.doc < b.doc)
  }

  scorers.zip(values).foreach{ case (s, v) => pq.insertWithOverflow(new ScorerDoc(s, -1, v)) }

  override def docID() = doc

  override def score(): Float = scoreValue

  private def doScore(): Float = {
    var sum = 0.0f
    var matchValue = 0.0f
    var cnt = 0
    if (doc < DocIdSetIterator.NO_MORE_DOCS) {
      var top = pq.top
      while (top.doc == doc) {
        sum += top.scorer.score()
        matchValue += top.value
        cnt += 1
        top.doc = top.scorer.nextDoc()
        top = pq.updateTop()
      }
    }
    if (matchValue < threshold) 0.0f else sum * coordFactors(cnt)
  }

  override def nextDoc(): Int = advance(0)

  override def advance(target: Int): Int = {
    if (doc < DocIdSetIterator.NO_MORE_DOCS) {
      doc = if (target <= doc) doc + 1 else target
    }
    scoreValue = 0.0f
    var top = pq.top
    while (scoreValue <= 0.0f && doc < DocIdSetIterator.NO_MORE_DOCS) {
      while (top.doc < doc) {
        top.doc = top.scorer.advance(doc)
        top = pq.updateTop()
      }
      doc = top.doc
      scoreValue = doScore
      top = pq.top
    }
    doc
  }
}

class BooleanNotScorer(weight: Weight, scorer: Scorer, prohibited: Array[Scorer]) extends Scorer(weight) {

  private var doc = -1

  override def docID() = doc

  override def score(): Float = scorer.score()

  override def nextDoc(): Int = advance(0)

  override def advance(target: Int): Int = {
    doc = scorer.advance(target)
    while (doc < DocIdSetIterator.NO_MORE_DOCS && isProhibited) {
      doc = scorer.advance(0)
    }
    doc
  }

  private def isProhibited = {
    prohibited.exists{ n =>
      if (n.docID < doc) n.advance(doc)
      (n.docID == doc)
    }
  }
}
