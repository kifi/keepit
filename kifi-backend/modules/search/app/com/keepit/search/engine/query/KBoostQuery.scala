package com.keepit.search.engine.query

import com.keepit.common.logging.Logging
import com.keepit.search.query.{ BoostWeight, BoostQuery }
import org.apache.lucene.index.AtomicReaderContext
import org.apache.lucene.search._
import org.apache.lucene.util.Bits
import scala.collection.mutable.ArrayBuffer

class KBoostQuery(override val textQuery: Query, override val boosterQuery: Query, val boosterStrength: Float) extends BoostQuery with ProjectableQuery {

  override def createWeight(searcher: IndexSearcher): Weight = new KBoostWeight(this, searcher)

  override protected val name = "KBoost"

  def project(fields: Set[String]): Query = new KBoostQuery(project(textQuery, fields), boosterQuery, boosterStrength)

  override def recreate(rewrittenTextQuery: Query, rewrittenBoosterQuery: Query): Query = {
    new KBoostQuery(rewrittenTextQuery, rewrittenBoosterQuery, boosterStrength)
  }
}

class KBoostWeight(override val query: KBoostQuery, override val searcher: IndexSearcher) extends BoostWeight with KWeight with Logging {

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

      val ret = new ComplexExplanation()
      result.setDescription("KBoost, product of:")
      result.setValue(score)
      result.setMatch(true)
    } else {
      result.setDescription("KBoost, doesn't match id %d".format(doc))
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

  override def scorer(context: AtomicReaderContext, acceptDocs: Bits): Scorer = {
    throw new UnsupportedOperationException()
  }

  def getWeights(out: ArrayBuffer[(Weight, Float)]): Unit = {
    textWeight.asInstanceOf[KWeight].getWeights(out)
    out += ((boosterWeight, 0.0f)) // weight = 0 since booster query should not be counted in percent match
  }
}
