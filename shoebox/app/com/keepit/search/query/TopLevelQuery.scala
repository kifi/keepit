package com.keepit.search.query

import com.keepit.common.logging.Logging
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

class TopLevelQuery(val textQuery: Query, val semanticVectorQuery: Option[Query], val proximityQuery: Option[Query], val enableCoord: Boolean) extends Query2 {

  override def createWeight2(searcher: Searcher): Weight = new TopLevelWeight(this, searcher)

  override def rewrite(reader: IndexReader): Query = {
    val rewrittenTextQ = textQuery.rewrite(reader)
    if (rewrittenTextQ eq textQuery) this
    else new TopLevelQuery(textQuery.rewrite(reader), semanticVectorQuery, proximityQuery, enableCoord)
  }

  override def extractTerms(out: JSet[Term]): Unit = {
    textQuery.extractTerms(out)
    semanticVectorQuery.foreach(_.extractTerms(out))
    proximityQuery.foreach(_.extractTerms(out))

  }

  override def toString(s: String) = {
    "TopLevel(%s, %s, %s)".format(textQuery.toString(s), semanticVectorQuery.map(_.toString(s)), proximityQuery.map(_.toString(s)))
  }

  override def equals(obj: Any): Boolean = obj match {
    case query: TopLevelQuery => (textQuery == query.textQuery) && (semanticVectorQuery == query.semanticVectorQuery) && (proximityQuery == query.semanticVectorQuery)
    case _ => false
  }

  override def hashCode(): Int = textQuery.hashCode() + semanticVectorQuery.hashCode() + proximityQuery.hashCode()
}

class TopLevelWeight(query: TopLevelQuery, searcher: Searcher) extends Weight with Logging {

  val textWeight = query.textQuery.createWeight(searcher)
  val semanticVectorWeight = query.semanticVectorQuery.map(_.createWeight(searcher))
  val proximityWeight = query.proximityQuery.map(_.createWeight(searcher))

  override def getQuery() = query
  override def getValue() = query.getBoost()
  override def scoresDocsOutOfOrder() = false

  override def sumOfSquaredWeights() = {
    val sum = textWeight.sumOfSquaredWeights + semanticVectorWeight.map(_.sumOfSquaredWeights).getOrElse(0.0f) + proximityWeight.map(_.sumOfSquaredWeights).getOrElse(0.0f)
    val value = query.getBoost()
    (sum * value * value)
  }

  override def normalize(norm: Float) {
    val n = norm * getValue()
    textWeight.normalize(n)
    semanticVectorWeight.foreach(_.normalize(n))
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

        semanticVectorWeight.map{ semanticVectorWeight =>
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
      // main scorer has to implement Coordinator trait
      val mainScorer = if (textScorer.isInstanceOf[Coordinator]) {
        if (getQuery.enableCoord) {
          textScorer.asInstanceOf[Scorer with Coordinator]
        } else {
          QueryUtil.toScorerWithCoordinator(textScorer) // hide textScorer's coord value by wrapping the constant coordinator
        }
      } else {
        QueryUtil.toScorerWithCoordinator(textScorer)
      }
      val semanticVectorScorer = semanticVectorWeight.map(_.scorer(reader, true, false))
      val proximityScorer = proximityWeight.map(_.scorer(reader, true, false))
      (semanticVectorScorer, proximityScorer) match {
        case (Some(semanticVectorScorer), Some(proximityScorer)) =>
          new TopLevelScorer2(this, mainScorer, semanticVectorScorer, proximityScorer)
        case (Some(semanticVectorScorer), None) =>
          new TopLevelScorer1(this, mainScorer, semanticVectorScorer)
        case (None, Some(proximityScorer)) =>
          new TopLevelScorer1(this, mainScorer, proximityScorer)
        case (None, None) =>
          new TopLevelScorer(this, mainScorer)
      }
    }
  }
}

class TopLevelScorer(weight: TopLevelWeight, textScorer: Scorer with Coordinator) extends Scorer(weight) with Logging {
  protected var doc = -1
  protected var scoredDoc = -1
  protected var scr = 0.0f

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
      try {
        scr = textScorer.score() * textScorer.coord
      } catch {
        case e: Exception =>
          log.error("scorer error: scoredDoc=%d doc=%doc textScorer.docID=%s exception=%s".format(scoredDoc, doc, textScorer.docID, e.toString))
          scr = 0.0f
      }
      scoredDoc = doc
    }
    scr
  }
}

class TopLevelScorer1(weight: TopLevelWeight, textScorer: Scorer with Coordinator, auxScorer: Scorer) extends TopLevelScorer(weight, textScorer) {
  override def score(): Float = {
    if (doc != scoredDoc) {
      try {
        var sum = textScorer.score()

        if (auxScorer.docID() < doc) auxScorer.advance(doc)
        if (auxScorer.docID() == doc) {
          sum += auxScorer.score()
        }
        scr = sum * textScorer.coord
      } catch {
        case e: Exception =>
          log.error("scorer error: scoredDoc=%d doc=%d textScorer.docID=%s exception=%s".format(scoredDoc, doc, textScorer.docID, e.toString))
          scr = 0.0f
      }
      scoredDoc = doc
    }
    scr
  }
}

class TopLevelScorer2(weight: TopLevelWeight, textScorer: Scorer with Coordinator, auxScorer1: Scorer, auxScorer2: Scorer ) extends TopLevelScorer(weight, textScorer) {
  override def score(): Float = {
    if (doc != scoredDoc) {
      try {
        var sum = textScorer.score()

        if (auxScorer1.docID() < doc) auxScorer1.advance(doc)
        if (auxScorer1.docID() == doc) {
          sum += auxScorer1.score()
        }
        if (auxScorer2.docID() < doc) auxScorer2.advance(doc)
        if (auxScorer2.docID() == doc) {
          sum += auxScorer2.score()
        }

        scr = sum * textScorer.coord
      } catch {
        case e: Exception =>
          log.error("scorer error: scoredDoc=%d doc=%d textScorer.docID=%s exception=%s".format(scoredDoc, doc, textScorer.docID, e.toString))
          scr = 0.0f
      }
      scoredDoc = doc
    }
    scr
  }
}

