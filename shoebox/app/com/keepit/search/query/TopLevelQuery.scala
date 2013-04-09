package com.keepit.search.query

import com.keepit.common.logging.Logging
import org.apache.lucene.index.AtomicReaderContext
import org.apache.lucene.index.DocsAndPositionsEnum
import org.apache.lucene.index.IndexReader
import com.keepit.search.index.Searcher
import org.apache.lucene.index.Term
import org.apache.lucene.search.ComplexExplanation
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import org.apache.lucene.search.Explanation
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.Query
import org.apache.lucene.search.Scorer
import org.apache.lucene.search.Weight
import org.apache.lucene.search.similarities.Similarity
import org.apache.lucene.util.Bits
import org.apache.lucene.util.PriorityQueue
import org.apache.lucene.util.ToStringUtils
import java.util.{Set => JSet}
import java.lang.{Float => JFloat}
import scala.collection.JavaConversions._
import scala.math._

class TopLevelQuery(val textQuery: Query, val auxQueries: Array[Query], val enableCoord: Boolean) extends Query {

  override def createWeight(searcher: IndexSearcher): Weight = new TopLevelWeight(this, searcher.asInstanceOf[Searcher])

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
  override def scoresDocsOutOfOrder() = false

  private def queryNorm(sum: Float): Float = {
    var norm = searcher.getSimilarity.queryNorm(sum)
        if (norm == Float.PositiveInfinity || norm == Float.NaN) norm = 1.0f
        norm
  }

  override def getValueForNormalization() = {
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

  override def normalize(norm: Float, topLevelBoost: Float) {
    val boost = topLevelBoost * query.getBoost
    // normalize each weight individually, then take the global normalization into account
    val textNorm = queryNorm(textWeight.getValueForNormalization)
    textWeight.normalize(textNorm * norm, boost)
    auxWeights.foreach{ w =>
      val auxNorm = queryNorm(w.getValueForNormalization)
      w.normalize(auxNorm * norm, boost)
    }

    textWeight.normalize(norm, boost)
    auxWeights.foreach(_.normalize(norm, boost))
  }

  override def explain(context: AtomicReaderContext, doc: Int) = {
    val sc = scorer(context, true, false, context.reader.getLiveDocs);
    val exists = (sc != null && sc.advance(doc) == doc);
    val result = new ComplexExplanation()
    var ret = if (exists) {
      val score = sc.score
      val coordFactor = sc.asInstanceOf[Coordinator].coord

      val ret = new ComplexExplanation()
      ret.setDescription("top level, product of:")
      ret.setValue(score)
      ret.setMatch(true)

      ret.addDetail(result)
      ret.addDetail(new Explanation(coordFactor, "coord factor"))

      result.setDescription("sum of:")
      result.setValue(score/coordFactor)
      result.setMatch(true)
      ret
    } else {
      result.setDescription("top level, doesn't match id %d".format(doc))
      result.setValue(0)
      result.setMatch(false)
      result
    }

    var sum = 0.0f
    val eTxt = textWeight.explain(context, doc)
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
          val eSV = w.explain(context, doc)
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

  override def scorer(context: AtomicReaderContext, scoreDocsInOrder: Boolean, topScorer: Boolean, acceptDocs: Bits): Scorer = {
    val textScorer = textWeight.scorer(context, true, false, acceptDocs)
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
      new TopLevelScorer(this, mainScorer, auxWeights.flatMap{ w => Option(w.scorer(context, true, false, acceptDocs)) })
    }
  }
}

class TopLevelScorer(weight: TopLevelWeight, textScorer: Scorer with Coordinator, auxScorers: Array[Scorer])
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
  override def freq(): Int = 1

  override def coord = textScorer.coord
}
