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

class TopLevelQuery(val textQuery: Query, val auxQueries: Array[Query], val enableCoord: Boolean) extends Query2 {

  override def createWeight2(searcher: Searcher): Weight = new TopLevelWeight(this, searcher)

  override def rewrite(reader: IndexReader): Query = {
    val rewrittenTextQuery = textQuery.rewrite(reader)
    val rewrittenAuxQueries = auxQueries.map{ q => q.rewrite(reader) }

    val textQueryUnchanged = (rewrittenTextQuery eq textQuery)
    val auxQueriesUnchanged = rewrittenAuxQueries.zip(auxQueries).forall{ case (r, q) => r eq q }

    if (textQueryUnchanged && auxQueriesUnchanged) this
    else new TopLevelQuery(rewrittenTextQuery, rewrittenAuxQueries, enableCoord)
  }

  override def extractTerms(out: JSet[Term]): Unit = {
    textQuery.extractTerms(out)
    auxQueries.foreach(_.extractTerms(out))
  }

  override def toString(s: String) = {
    "TopLevel(%s, %s, %s)".format(
      textQuery.toString(s),
      auxQueries.map(_.toString(s)).mkString("(",",",")"),
      enableCoord)
  }

  override def equals(obj: Any): Boolean = obj match {
    case query: TopLevelQuery =>
      textQuery == query.textQuery &&
      auxQueries.length == query.auxQueries.length &&
      (auxQueries.length == 0 || auxQueries.zip(query.auxQueries).forall{ case (a, b) => a.equals(b) })
    case _ => false
  }

  override def hashCode(): Int = textQuery.hashCode() + auxQueries.foldLeft(0){ (sum, q) => sum + q.hashCode() }
}

class TopLevelWeight(query: TopLevelQuery, searcher: Searcher) extends Weight with Logging {

  val textWeight: Weight = query.textQuery.createWeight(searcher)
  val auxWeights: Array[Weight] = query.auxQueries.map(_.createWeight(searcher))

  override def getQuery() = query
  override def getValue() = query.getBoost()
  override def scoresDocsOutOfOrder() = false

  override def sumOfSquaredWeights() = {
    // take boost values only, each weight will be normalized individually.
    // this makes tuning (balancing the weighting of text and auxiliary queries) more intuitive.
    var textBoost = textWeight.getQuery.getBoost
    val sum = auxWeights.foldLeft(textBoost * textBoost){ (sum, w) =>
      val auxBoost = w.getQuery.getBoost
      sum + (auxBoost * auxBoost)
    }
    val value = query.getBoost
    (sum * value * value)
  }

  private def queryNorm(sum: Float): Float = {
    var norm = searcher.getSimilarity.queryNorm(sum)
    if (norm == Float.PositiveInfinity || norm == Float.NaN) norm = 1.0f
    norm
  }

  override def normalize(norm: Float) {
    // normalize each weigth individually, then take the global normalization into account
    val textNorm = queryNorm(textWeight.sumOfSquaredWeights)
    textWeight.normalize(textNorm * norm)
    auxWeights.foreach{ w =>
      val auxNorm = queryNorm(w.sumOfSquaredWeights)
      w.normalize(auxNorm * norm)
    }
  }

  override def explain(reader: IndexReader, doc: Int) = {
    val sc = scorer(reader, true, false);
    val exists = (sc != null && sc.advance(doc) == doc);

    val result = new ComplexExplanation()
    if (exists) {
      result.setDescription("top level, sum of:")
      val score = sc.score
      result.setValue(score)
      result.setMatch(true)
    } else {
      result.setDescription("top level, doesn't match id %d".format(doc))
      result.setValue(0)
      result.setMatch(false)
    }

    var sum = 0.0f
    val eTxt = textWeight.explain(reader, doc)
    eTxt.isMatch() match {
      case false =>
        val r = new Explanation(0.0f, "no match in (" + textWeight.getQuery.toString() + ")")
        r.addDetail(eTxt)
        result.addDetail(r)
        result.setMatch(false)
        result.setValue(sum)
        result.setDescription("Failure to meet condition of textQuery")
      case true =>
        result.addDetail(eTxt)

        auxWeights.map{ w =>
          val eSV = w.explain(reader, doc)
          eSV.isMatch() match {
            case true =>
              result.addDetail(eSV)
              sum += eSV.getValue
            case false =>
              val r = new Explanation(0.0f, "no match in (" + w.getQuery.toString() + ")")
              r.addDetail(eSV)
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
      new TopLevelScorer(this, mainScorer, auxWeights.flatMap{ w => Option(w.scorer(reader, true, false)) })
    }
  }
}

class TopLevelScorer(weight: TopLevelWeight, textScorer: Scorer with Coordinator, auxScorers: Array[Scorer]) extends Scorer(weight) with Logging {
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
  override def score(): Float = {
    if (doc != scoredDoc) {
      try {
        var sum = textScorer.score()
        var i = 0
        while (i < auxScorers.length) {
          val auxScorer = auxScorers(i)
          if (auxScorer.docID() < doc) auxScorer.advance(doc)
          if (auxScorer.docID() == doc) {
            sum += auxScorer.score()
          }
          i += 1
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
