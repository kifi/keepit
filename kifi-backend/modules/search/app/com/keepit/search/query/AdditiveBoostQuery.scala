package com.keepit.search.query

import com.keepit.common.logging.Logging
import org.apache.lucene.index.AtomicReaderContext
import org.apache.lucene.index.IndexReader
import org.apache.lucene.index.Term
import org.apache.lucene.search.Query
import org.apache.lucene.search.Scorer
import org.apache.lucene.search.Weight
import org.apache.lucene.search.ComplexExplanation
import org.apache.lucene.search.Explanation
import org.apache.lucene.search.DocIdSetIterator
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.util.Bits

class AdditiveBoostQuery(override val textQuery: Query, override val boosterQueries: Array[Query], override val enableCoord: Boolean) extends BoostQuery {

  override def createWeight(searcher: IndexSearcher): Weight = new AdditiveBoostWeight(this, searcher)

  override protected val name = "AdditiveBoost"

  override def recreate(rewrittenTextQuery: Query, rewrittenBoosterQueries: Array[Query]): Query = {
    new AdditiveBoostQuery(rewrittenTextQuery, rewrittenBoosterQueries, enableCoord)
  }
}

class AdditiveBoostWeight(override val query: AdditiveBoostQuery, override val searcher: IndexSearcher) extends BoostWeight with Logging {

  override def getValueForNormalization() = {
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

  override def normalize(norm: Float, topLevelBoost: Float) {
    val boost = topLevelBoost * query.getBoost
    // normalize each weight individually, then take the global normalization into account
    val textNorm = queryNorm(textWeight.getValueForNormalization)
    textWeight.normalize(textNorm * norm, boost)
    boosterWeights.foreach{ w =>
      val boosterNorm = queryNorm(w.getValueForNormalization)
      w.normalize(boosterNorm * norm, boost)
    }
  }

  override def explain(context: AtomicReaderContext, doc: Int) = {
    val sc = scorer(context, true, false, context.reader.getLiveDocs).asInstanceOf[AdditiveBoostScorer]
    val exists = (sc != null && sc.advance(doc) == doc)

    val result = new ComplexExplanation()
    result.setMatch(exists)
    if (exists) {
      result.setDescription("additive boost, sum of:")
      result.setValue(sc.score)
    } else {
      result.setDescription("additive boost, doesn't match id %d".format(doc))
      result.setValue(0)
    }

    val eTxt = textWeight.explain(context, doc)
    eTxt.isMatch() match {
      case false =>
        val r = new ComplexExplanation()
        r.setDescription("Failure to meet condition of textQuery (" + textWeight.getQuery.toString() + ")")
        r.setMatch(false)
        r.setValue(0.0f)
        r.addDetail(eTxt)
        result.addDetail(r)
      case true =>
        result.addDetail(eTxt)

        val sumExpl = new ComplexExplanation()
        sumExpl.setDescription("sum of:")
        var sum = 0.0f
        boosterWeights.map{ w =>
          val e = w.explain(context, doc)
          e.isMatch() match {
            case true =>
              sumExpl.addDetail(e)
              sum += e.getValue
            case false =>
              val r = new Explanation(0.0f, "no match in (" + w.getQuery.toString() + ")")
              r.addDetail(e)
              sumExpl.addDetail(r)
          }
        }
        sumExpl.setValue(sum)

        val textFactor = if (sc.docID == sc.textScorer.docID) sc.textScorer.coord else 0.0f
        val coordExpl = new ComplexExplanation()
        coordExpl.setDescription("product of:")
        coordExpl.setValue(sumExpl.getValue * textFactor)
        coordExpl.setMatch(true)
        coordExpl.addDetail(new Explanation(textFactor, "text factor"))
        coordExpl.addDetail(sumExpl)

        result.addDetail(coordExpl)
    }
    result
  }

  override def scorer(context: AtomicReaderContext, scoreDocsInOrder: Boolean, topScorer: Boolean, liveDocs: Bits): Scorer = {
    val textScorer = textWeight.scorer(context, true, false, liveDocs)
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
      new AdditiveBoostScorer(this, mainScorer, boosterWeights.flatMap{ w => Option(w.scorer(context, true, false, liveDocs)) })
    }
  }
}

class AdditiveBoostScorer(weight: AdditiveBoostWeight, val textScorer: Scorer with Coordinator, boosterScorers: Array[Scorer])
extends Scorer(weight) with Logging {
  private[this] var doc = -1
  private[this] var scoredDoc = -1
  private[this] var scr = 0.0f

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

  override def freq(): Int = 1
}
