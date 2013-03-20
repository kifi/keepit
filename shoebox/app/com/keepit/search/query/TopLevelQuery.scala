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

class TopLevelQuery(val textQuery: Query, val semanticVectorQuery: Option[Query], val proximityQuery: Option[Query], val enableCoord: Boolean) extends Query {

  override def createWeight(searcher: IndexSearcher): Weight = new TopLevelWeight(this, searcher.asInstanceOf[Searcher])

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
  override def scoresDocsOutOfOrder() = false

  override def getValueForNormalization() = {
    val sum = textWeight.getValueForNormalization + semanticVectorWeight.map(_.getValueForNormalization).getOrElse(0.0f) + proximityWeight.map(_.getValueForNormalization).getOrElse(0.0f)
    val value = query.getBoost()
    (sum * value * value)
  }

  override def normalize(norm: Float, topLevelBoost: Float) {
    val boost = topLevelBoost * query.getBoost
    textWeight.normalize(norm, boost)
    semanticVectorWeight.foreach(_.normalize(norm, boost))
    proximityWeight.foreach(_.normalize(norm, boost))
  }

  override def explain(context: AtomicReaderContext, doc: Int) = {
    val sc = scorer(context, true, false, context.reader.getLiveDocs);
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

        semanticVectorWeight.map{ semanticVectorWeight =>
          val eSV = semanticVectorWeight.explain(context, doc)
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
          val eProx = proximityWeight.explain(context, doc)
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
      val semanticVectorScorer = semanticVectorWeight.map(_.scorer(context, true, false, acceptDocs))
      val proximityScorer = proximityWeight.map(_.scorer(context, true, false, acceptDocs))
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
  override def freq(): Int = 1
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

