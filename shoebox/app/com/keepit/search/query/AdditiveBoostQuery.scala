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

class AdditiveBoostQuery(override val textQuery: Query, override val boosterQueries: Array[Query]) extends BoostQuery {

  override def createWeight2(searcher: Searcher): Weight = new AdditiveBoostWeight(this, searcher)

  override protected val name = "AdditiveBoost"

    override def recreate(rewrittenTextQuery: Query, rewrittenBoosterQueries: Array[Query]): Query = {
      val q = new AdditiveBoostQuery(rewrittenTextQuery, rewrittenBoosterQueries)
      q.enableCoord = enableCoord
      q
  }
}

class AdditiveBoostWeight(override val query: AdditiveBoostQuery, override val searcher: Searcher) extends BoostWeight with Logging {

  override def sumOfSquaredWeights() = {
    // take boost values only, each weight will be normalized individually.
    // this makes tuning (balancing the weighting of text and booster queries) more intuitive.
    var textBoost = textWeight.getQuery.getBoost
    val sum = boosterWeights.foldLeft(textBoost * textBoost){ (sum, w) =>
      val boost = w.getQuery.getBoost
      sum + (boost * boost)
    }
    val value = query.getBoost
    (sum * value * value)
  }

  override def normalize(norm: Float) {
    val boost = query.getBoost
    // normalize each weight individually, then take the global normalization into account
    val textNorm = queryNorm(textWeight.sumOfSquaredWeights)
    textWeight.normalize(textNorm * norm * boost)
    boosterWeights.foreach{ w =>
      val boosterNorm = queryNorm(w.sumOfSquaredWeights)
      w.normalize(boosterNorm * norm * boost)
    }
  }

  override def explain(reader: IndexReader, doc: Int) = {
    val sc = scorer(reader, true, false)
    val exists = (sc != null && sc.advance(doc) == doc)

    val result = new ComplexExplanation()
    var ret = if (exists) {
      val score = sc.score
      val coordFactor = sc.asInstanceOf[Coordinator].coord

      val ret = new ComplexExplanation()
      ret.setDescription("additive boost, product of:")
      ret.setValue(score)
      ret.setMatch(true)

      ret.addDetail(result)
      ret.addDetail(new Explanation(coordFactor, "coord factor"))

      result.setDescription("sum of:")
      result.setValue(score/coordFactor)
      result.setMatch(true)
      ret
    } else {
      result.setDescription("additive boost, doesn't match id %d".format(doc))
      result.setValue(0)
      result.setMatch(false)
      result
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

        boosterWeights.map{ w =>
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
    ret
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
      new AdditiveBoostScorer(this, mainScorer, boosterWeights.flatMap{ w => Option(w.scorer(reader, true, false)) })
    }
  }
}

class AdditiveBoostScorer(weight: AdditiveBoostWeight, textScorer: Scorer with Coordinator, boosterScorers: Array[Scorer])
extends Scorer(weight) with Coordinator with Logging {
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
        while (i < boosterScorers.length) {
          val boosterScorer = boosterScorers(i)
          if (boosterScorer.docID() < doc) boosterScorer.advance(doc)
          if (boosterScorer.docID() == doc) {
            sum += boosterScorer.score()
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

  override def coord = textScorer.coord
}
