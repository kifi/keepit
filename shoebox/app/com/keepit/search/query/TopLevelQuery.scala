package com.keepit.search.query

import com.keepit.search.index.Searcher
import org.apache.lucene.index.IndexReader
import org.apache.lucene.index.Term
import org.apache.lucene.index.TermPositions
import org.apache.lucene.search.Query
import org.apache.lucene.search.Scorer
import org.apache.lucene.search.Weight
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

class TopLevelQuery(val textQuery: Query, val semanticVectorQuery: Query, val proximityQuery: Option[Query]) extends Query2 {

  override def createWeight2(searcher: Searcher): Weight = new TopLevelWeight(this, searcher)

  override def rewrite(reader: IndexReader): Query = this

  override def extractTerms(out: JSet[Term]): Unit = {
    textQuery.extractTerms(out)
    semanticVectorQuery.extractTerms(out)
    proximityQuery.foreach( _.extractTerms(out) )

  }

  override def toString(s: String) = {
    proximityQuery match {
      case Some(proximityQuery) => "TopLevel(%s, %s, %s)".format(textQuery.toString(s), semanticVectorQuery.toString(s), proximityQuery.toString(s))
      case None => "TopLevel(%s, %s)".format(textQuery.toString(s), semanticVectorQuery.toString(s))
    }
  }

  override def equals(obj: Any): Boolean = obj match {
    case query: TopLevelQuery => (textQuery == query.textQuery) && (semanticVectorQuery == query.semanticVectorQuery) && (proximityQuery == query.semanticVectorQuery)
    case _ => false
  }

  override def hashCode(): Int = textQuery.hashCode() + semanticVectorQuery.hashCode() + proximityQuery.hashCode()
}

class TopLevelWeight(query: TopLevelQuery, searcher: Searcher) extends Weight {

  val textWeight = query.textQuery.createWeight(searcher)
  val semanticVectorWeight = query.semanticVectorQuery.createWeight(searcher)
  val proximityWeight = query.proximityQuery.map(_.createWeight(searcher))

  override def getQuery() = query
  override def getValue() = query.getBoost()
  override def scoresDocsOutOfOrder() = false

  override def sumOfSquaredWeights() = {
    var sum = textWeight.sumOfSquaredWeights + semanticVectorWeight.sumOfSquaredWeights
    proximityWeight match {
      case Some(proximityWeight) => sum += proximityWeight.sumOfSquaredWeights
      case None =>
    }
    val value = query.getBoost()
    (sum * value * value)
  }

  override def normalize(norm: Float) {
    val n = norm * getValue()
    textWeight.normalize(n)
    semanticVectorWeight.normalize(n)
    proximityWeight.foreach(_.normalize(n))
  }

  override def explain(reader: IndexReader, doc: Int) = {
    val sc = scorer(reader, true, false);
    val exists = (sc != null && sc.advance(doc) == doc);

    val result = new ComplexExplanation()
    if (exists) {
      result.setDescription("top level, sum of:")
      val score = sc.score
      val boost = query.getBoost
      result.setValue(score * boost)
      result.setMatch(true)
      result.addDetail(new Explanation(score, "top level score"))
      result.addDetail(new Explanation(boost, "boost"))
    } else {
      result.setDescription("top level, doesn't match id %d".format(doc))
      result.setValue(0)
      result.setMatch(false)
    }
    result

    var sum = 0.0f
    val eTxt = textWeight.explain(reader, doc)
    eTxt.isMatch() match {
      case false =>
        val r = new Explanation(0.0f, "no match in (" + textWeight.getQuery.toString() + ")")
        result.addDetail(r)
        result.setMatch(false)
        result.setValue(sum)
        result.setDescription("Failure to meet condition of textQuery");
      case true =>
        result.addDetail(eTxt)

        val eSV = semanticVectorWeight.explain(reader, doc)
        eSV.isMatch() match {
          case true =>
            result.addDetail(eSV)
            sum += eSV.getValue
          case false =>
            val r = new Explanation(0.0f, "no match in (" + semanticVectorWeight.getQuery.toString() + ")")
            r.addDetail(eSV)
            result.addDetail(r)
        }

        proximityWeight.map{ proximityWeight =>
          val eProx = proximityWeight.explain(reader, doc)
          eProx.isMatch() match {
            case true =>
              result.addDetail(eProx)
              sum += eProx.getValue
            case false =>
              val r = new Explanation(0.0f, "no match in (" + proximityWeight.getQuery.toString() + ")")
              r.addDetail(eProx)
              result.addDetail(r)
          }
        }
    }
    result
  }

  override def scorer(reader: IndexReader, scoreDocsInOrder: Boolean, topScorer: Boolean): Scorer = {
    val textScorer = textWeight.scorer(reader, true, false)
    if (textScorer == null) null
    else {
      val semanticVectorScorer = semanticVectorWeight.scorer(reader, true, false)
      proximityWeight match {
        case Some(proximityWeight) =>
          new TopLevelScorerWithProximity(this, textScorer, semanticVectorScorer, proximityWeight.scorer(reader, true, false))
        case None =>
          new TopLevelScorerNoProximity(this, textScorer, semanticVectorScorer)
      }
    }
  }
}

class TopLevelScorer(weight: TopLevelWeight, textScorer: Scorer) extends Scorer(weight) {
  protected var doc = -1
  protected var scoredDoc = -1
  protected var scr = 0.0f
  protected var textScore = 0.0f
  protected var semanticVectorScore = 0.0f
  protected var proximityScore = 0.0f

  private[this] val textBoost = weight.textWeight.getValue
  private[this] val semanticVectorBoost = weight.semanticVectorWeight.getValue
  private[this] val proximityBoost = weight.proximityWeight match {
    case Some(proximityWeight) => proximityWeight.getValue
    case None => 1.0f
  }

  override def docID(): Int = doc
  override def nextDoc(): Int = {
    doc = textScorer.nextDoc()
    doc
  }
  override def advance(target: Int): Int = {
    doc = textScorer.advance(target)
    doc
  }
  override def score() = {
    if (doc != scoredDoc) {
      scoredDoc = doc
      textScore = textScorer.score()
      scr = textScore
    }
    scr
  }

  def textRawScore = textScore / textBoost
  def semanticVectorRawScore = semanticVectorScore / semanticVectorBoost
  def proximityRawScore = proximityScore / proximityBoost
}

class TopLevelScorerNoProximity(weight: TopLevelWeight, textScorer: Scorer, semanticVectorScorer: Scorer) extends TopLevelScorer(weight, textScorer) {
  override def score(): Float = {
    if (doc != scoredDoc) {
      scoredDoc = doc
      textScore = textScorer.score()
      var sum = textScore
      if (semanticVectorScorer.docID() < doc) semanticVectorScorer.advance(doc)
      if (semanticVectorScorer.docID() == doc) {
        semanticVectorScore = semanticVectorScorer.score()
        sum += semanticVectorScore
      } else {
        semanticVectorScore = 0.0f
      }
      scr = sum
    }
    scr
  }
}

class TopLevelScorerWithProximity(weight: TopLevelWeight, textScorer: Scorer, semanticVectorScorer: Scorer, proximityScorer: Scorer) extends TopLevelScorer(weight, textScorer) {
  override def score(): Float = {
    if (doc != scoredDoc) {
      scoredDoc = doc
      textScore = textScorer.score()
      var sum = textScore
      if (semanticVectorScorer.docID() < doc) semanticVectorScorer.advance(doc)
      if (semanticVectorScorer.docID() == doc) {
        semanticVectorScore = semanticVectorScorer.score()
        sum += semanticVectorScore
      } else {
        semanticVectorScore = 0.0f
      }
      if (proximityScorer.docID() < doc) proximityScorer.advance(doc)
      if (proximityScorer.docID() == doc) {
        proximityScore = proximityScorer.score()
        sum += proximityScore
      } else {
        proximityScore = 0.0f
      }
      scr = sum
    }
    scr
  }
}

