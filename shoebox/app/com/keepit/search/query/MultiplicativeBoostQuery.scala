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

class MultiplicativeBoostQuery(override val textQuery: Query, override val boosterQueries: Array[Query], val boosterStrengths: Array[Float]) extends BoostQuery {

  override def createWeight2(searcher: Searcher): Weight = new MultiplicativeBoostWeight(this, searcher)

  override protected val name = "MultiplicativeBoost"

  override def recreate(rewrittenTextQuery: Query, rewrittenBoosterQueries: Array[Query]): Query = {
    val q = new MultiplicativeBoostQuery(rewrittenTextQuery, rewrittenBoosterQueries, boosterStrengths)
    q.enableCoord = enableCoord
    q
  }
}

class MultiplicativeBoostWeight(override val query: MultiplicativeBoostQuery, override val searcher: Searcher) extends BoostWeight with Logging {

  val boosterStrengths = query.boosterStrengths

  override def sumOfSquaredWeights() = textWeight.sumOfSquaredWeights()

  override def normalize(norm: Float) {
    textWeight.normalize(norm * query.getBoost)

    // normalize each boost weight individually
    boosterWeights.foreach{ w =>
      val boosterNorm = queryNorm(w.sumOfSquaredWeights)
      w.normalize(boosterNorm)
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
      ret.setDescription("multiplicative boost, product of:")
      ret.setValue(score)
      ret.setMatch(true)

      ret.addDetail(result)
      ret.addDetail(new Explanation(coordFactor, "coord factor"))

      result.setDescription("product of:")
      result.setValue(score/coordFactor)
      result.setMatch(true)
      ret
    } else {
      result.setDescription("multiplicative boost, doesn't match id %d".format(doc))
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

        boosterWeights.zip(boosterStrengths).map{ case (w, s) =>
          val eSV = w.explain(reader, doc)
          val r = eSV.isMatch() match {
            case true =>
              new Explanation((eSV.getValue * s + (1.0f - s)), s"boosting (stength=${s})")
            case false =>
              new Explanation(0.0f, "no match in (" + w.getQuery.toString() + ")")
          }
          r.addDetail(eSV)
          result.addDetail(r)
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
      new MultiplicativeBoostScorer(this, mainScorer,
        boosterWeights.flatMap{ w => Option(w.scorer(reader, true, false)) }, boosterStrengths)
    }
  }
}

class MultiplicativeBoostScorer(weight: MultiplicativeBoostWeight, textScorer: Scorer with Coordinator, boosterScorers: Array[Scorer], boosterStrengths: Array[Float])
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
        var score = textScorer.score()
        var i = 0
        while (i < boosterScorers.length) {
          val boosterScorer = boosterScorers(i)
          if (boosterScorer.docID() < doc) boosterScorer.advance(doc)
          if (boosterScorer.docID() == doc) {
            val s = boosterStrengths(i)
            score *= (boosterScorer.score() * s + (1.0f - s))
          }
          i += 1
        }
        scr = score
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
