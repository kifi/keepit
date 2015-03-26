package com.keepit.search.engine.query

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

class MultiplicativeBoostQuery(override val textQuery: Query, override val boosterQuery: Query, val boosterStrength: Float) extends BoostQuery {

  override def createWeight(searcher: IndexSearcher): Weight = new MultiplicativeBoostWeight(this, searcher)

  override protected val name = "MultiplicativeBoost"

  override def recreate(rewrittenTextQuery: Query, rewrittenBoosterQuery: Query): Query = {
    new MultiplicativeBoostQuery(rewrittenTextQuery, rewrittenBoosterQuery, boosterStrength)
  }
}

class MultiplicativeBoostWeight(override val query: MultiplicativeBoostQuery, override val searcher: IndexSearcher) extends BoostWeight with Logging {

  val boosterStrength = query.boosterStrength

  override def getValueForNormalization() = textWeight.getValueForNormalization

  override def normalize(norm: Float, topLevelBoost: Float) {
    val boost = topLevelBoost * query.getBoost
    textWeight.normalize(norm, boost)

    // normalize the boost weight
    val boosterNorm = queryNorm(boosterWeight.getValueForNormalization)
    boosterWeight.normalize(boosterNorm, 1.0f)
  }

  override def explain(context: AtomicReaderContext, doc: Int) = {
    val sc = scorer(context, context.reader.getLiveDocs)
    val exists = (sc != null && sc.advance(doc) == doc)

    val result = new ComplexExplanation()
    if (exists) {
      val score = sc.score

      result.setDescription("multiplicative boost, product of:")
      result.setValue(score)
      result.setMatch(true)
    } else {
      result.setDescription("multiplicative boost, doesn't match id %d".format(doc))
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
            new Explanation((e.getValue * boosterStrength + (1.0f - boosterStrength)), s"boosting (strength=${boosterStrength})")
          case false =>
            new Explanation((1.0f - boosterStrength), "no match in (" + boosterWeight.getQuery.toString() + ")")
        }
        r.addDetail(e)
        result.addDetail(r)
    }
    result
  }

  override def scorer(context: AtomicReaderContext, liveDocs: Bits): Scorer = {
    val textScorer = textWeight.scorer(context, liveDocs)
    if (textScorer == null) null
    else {
      new MultiplicativeBoostScorer(this, textScorer, boosterWeight.scorer(context, liveDocs), boosterStrength)
    }
  }
}

class MultiplicativeBoostScorer(weight: MultiplicativeBoostWeight, textScorer: Scorer, boosterScorer: Scorer, boosterStrength: Float)
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
          if (boosterScorer.docID() == doc) {
            score *= (boosterScorer.score() * boosterStrength + (1.0f - boosterStrength))
          } else {
            score *= (1.0f - boosterStrength) // boostscorer doesn't have this doc id.
          }
        } else {
          score *= (1.0f - boosterStrength)
        }
        scr = score
      } catch {
        case e: Exception =>
          log.error("scorer error: scoredDoc=%d doc=%d textScorer.docID=%s exception=%s stack=\n%s\n".format(scoredDoc, doc, textScorer.docID, e.toString, e.getStackTrace.mkString("", "\n", "\n")))
          scr = 0.0f
      }
      scoredDoc = doc
    }
    scr
  }

  override def freq(): Int = 1
  override def cost(): Long = textScorer.cost()
}
