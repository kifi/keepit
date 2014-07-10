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

class ExistenceBoostQuery(override val textQuery: Query, override val boosterQuery: Query, val boosterStrength: Float) extends BoostQuery {

  override def createWeight(searcher: IndexSearcher): Weight = new ExistenceBoostWeight(this, searcher)

  override protected val name = "ExistenceBoost"

  override def recreate(rewrittenTextQuery: Query, rewrittenBoosterQuery: Query): Query = {
    new ExistenceBoostQuery(rewrittenTextQuery, rewrittenBoosterQuery, boosterStrength)
  }
}

class ExistenceBoostWeight(override val query: ExistenceBoostQuery, override val searcher: IndexSearcher) extends BoostWeight with Logging {

  private[this] val mismatchWeight = (1.0f - query.boosterStrength)

  override def getValueForNormalization() = textWeight.getValueForNormalization

  override def normalize(norm: Float, topLevelBoost: Float) {
    val boost = topLevelBoost * query.getBoost
    textWeight.normalize(norm, boost)

    // normalize the boost weight
    val boosterNorm = queryNorm(boosterWeight.getValueForNormalization)
    boosterWeight.normalize(boosterNorm, 1.0f)
  }

  override def explain(context: AtomicReaderContext, doc: Int) = {
    val sc = scorer(context, true, false, context.reader.getLiveDocs)
    val exists = (sc != null && sc.advance(doc) == doc)

    val result = new ComplexExplanation()
    if (exists) {
      val score = sc.score

      val ret = new ComplexExplanation()
      result.setDescription("existence boost, product of:")
      result.setValue(score)
      result.setMatch(true)
    } else {
      result.setDescription("existence boost, doesn't match id %d".format(doc))
      result.setValue(0)
      result.setMatch(false)
    }

    val eTxt = textWeight.explain(context, doc)
    eTxt.isMatch() match {
      case false =>
        val r = new Explanation(0.0f, "no match in (" + textWeight.getQuery.toString() + ")")
        r.addDetail(eTxt)
        result.addDetail(r)
        result.setMatch(false)
        result.setValue(0.0f)
        result.setDescription("Failure to meet condition of textQuery")
      case true =>
        result.addDetail(eTxt)
        val e = boosterWeight.explain(context, doc)
        val r = e.isMatch() match {
          case true =>
            new Explanation(1.0f, s"boosting (strength=1.0)")
          case false =>
            new Explanation(mismatchWeight, "no match in (" + boosterWeight.getQuery.toString() + ")")
        }
        r.addDetail(e)
        result.addDetail(r)
    }
    result
  }

  override def scorer(context: AtomicReaderContext, scoreDocsInOrder: Boolean, topScorer: Boolean, liveDocs: Bits): Scorer = {
    val textScorer = textWeight.scorer(context, true, false, liveDocs)
    if (textScorer == null) null
    else {
      new ExistenceBoostScorer(this, textScorer, boosterWeight.scorer(context, true, false, liveDocs), mismatchWeight)
    }
  }
}

class ExistenceBoostScorer(weight: ExistenceBoostWeight, textScorer: Scorer, boosterScorer: Scorer, mismatchWeight: Float)
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
        var score = textScorer.score()
        if (boosterScorer != null) {
          if (boosterScorer.docID() < doc) boosterScorer.advance(doc)
          if (boosterScorer.docID() > doc) {
            score *= mismatchWeight
          }
        } else {
          score *= mismatchWeight
        }
        scr = score
      } catch {
        case e: Exception =>
          log.error("scorer error: scoredDoc=%d doc=%d textScorer.docID=%s exception=%s stack=\n%s\n".format(scoredDoc, doc, textScorer.docID, e.toString, e.getStackTraceString))
          scr = 0.0f
      }
      scoredDoc = doc
    }
    scr
  }

  override def freq(): Int = 1
  override def cost(): Long = textScorer.cost()
}
