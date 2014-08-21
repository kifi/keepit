package com.keepit.search.engine.query

import com.keepit.common.logging.Logging
import com.keepit.search.Searcher
import com.keepit.search.query.SemanticVectorQuery
import com.keepit.search.query.SemanticVectorScorer
import org.apache.lucene.index.AtomicReaderContext
import org.apache.lucene.index.IndexReader
import org.apache.lucene.index.Term
import org.apache.lucene.search._
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import org.apache.lucene.util.Bits
import java.util.{ Set => JSet }
import scala.collection.mutable.ArrayBuffer
import scala.math._
import collection.JavaConversions._

object KTextQuery {
  val tieBreakerMultiplier = 1.0f
}

class KTextQuery extends Query with Logging {

  private var mainQuery: Query = new DisjunctionMaxQuery(KTextQuery.tieBreakerMultiplier)
  private var semanticVectorQuery: Query = new DisjunctionMaxQuery(0.0f)

  def getSemanticVectorQuery = semanticVectorQuery

  var terms: Array[Term] = Array()
  var stems: Array[Term] = Array()

  val concatStems: ArrayBuffer[String] = ArrayBuffer()

  private[this] var totalSubQueryCnt: Int = 0

  def addQuery(query: Query, boost: Float = 1.0f): Unit = {
    query.setBoost(boost)
    mainQuery = mainQuery match {
      case disjunct: DisjunctionMaxQuery =>
        totalSubQueryCnt += 1
        disjunct.add(query)
        disjunct
      case _ =>
        log.error("TextQuery: DisjunctionMaxQuery match failed")
        throw new Exception("Failed to add personal query")
    }
  }

  private[this] var semanticBoost = 0.0f

  def setSemanticBoost(boost: Float): Unit = { semanticBoost = boost }

  def getSemanticBoost(): Float = semanticBoost

  def addSemanticVectorQuery(field: String, text: String): Unit = {
    val query = SemanticVectorQuery(new Term(field, text))
    semanticVectorQuery = semanticVectorQuery match {
      case disjunct: DisjunctionMaxQuery =>
        totalSubQueryCnt += 1
        disjunct.add(query)
        disjunct
      case _ =>
        log.error("KTextQuery: DisjunctionMaxQuery match failed")
        throw new Exception("Failed to add semanticVectorQuery")
    }
  }

  override def createWeight(searcher: IndexSearcher): Weight = {
    val svWeight = searcher match {
      case s: Searcher if (s.hasSemanticContext && semanticBoost > 0.0f) => semanticVectorQuery.createWeight(searcher)
      case _ => null
    }
    new KTextWeight(this, mainQuery.createWeight(searcher), svWeight)
  }

  override def rewrite(reader: IndexReader): Query = {
    val rewrittenMainQuery = mainQuery.rewrite(reader)
    val rewrittenSemanticVectorQuery = semanticVectorQuery.rewrite(reader)
    if ((mainQuery eq rewrittenMainQuery) && (semanticVectorQuery eq rewrittenSemanticVectorQuery)) this
    else {
      val rewritten = this.clone().asInstanceOf[KTextQuery]
      rewritten.mainQuery = rewrittenMainQuery
      rewritten.semanticVectorQuery = rewrittenSemanticVectorQuery
      rewritten
    }
  }

  override def extractTerms(out: JSet[Term]): Unit = {
    mainQuery.extractTerms(out)
    semanticVectorQuery.extractTerms(out)
  }

  override def toString(s: String) = {
    s"KTextQuery(${mainQuery.toString(s)} ${semanticVectorQuery.toString(s)})"
  }

  override def equals(obj: Any): Boolean = obj match {
    case query: KTextQuery => (mainQuery.equals(query.mainQuery))
    case _ => false
  }

  override def hashCode(): Int = mainQuery.hashCode()

  def isEmpty: Boolean = { totalSubQueryCnt == 0 }
}

class KTextWeight(query: KTextQuery,
    mainWeight: Weight,
    semanticWeight: Weight) extends Weight with KWeight with Logging {

  override def getQuery() = query
  override def scoresDocsOutOfOrder() = false

  override def getValueForNormalization(): Float = {
    if (semanticWeight != null) semanticWeight.getValueForNormalization() // for side effect

    val sub = if (mainWeight != null) mainWeight.getValueForNormalization() else 1.0f
    val boost = query.getBoost()

    (sub * boost * boost)
  }

  override def normalize(norm: Float, topLevelBoost: Float): Unit = {
    if (semanticWeight != null) semanticWeight.normalize(1.0f, 1.0f) // for side effect

    val boost = query.getBoost
    if (mainWeight != null) mainWeight.normalize(norm, topLevelBoost * boost)
  }

  override def explain(context: AtomicReaderContext, doc: Int): Explanation = {
    val reader = context.reader
    val sc = scorer(context, true, false, reader.getLiveDocs);
    val exists = (sc != null && sc.advance(doc) == doc);

    val result = new ComplexExplanation()
    if (exists) {
      result.setDescription("KTextQuery")
      result.setValue(sc.score)
      result.setMatch(true)
      if (mainWeight != null) {
        val exp = mainWeight.explain(context, doc)
        if (exp.getValue() > 0.0f) result.addDetail(exp)
      }
      if (semanticWeight != null) {
        val exp = semanticWeight.explain(context, doc)
        if (exp.getValue() > 0.0f) result.addDetail(exp)
      }
    } else {
      result.setDescription("KTextQuery, doesn't match id %d".format(doc))
      result.setValue(0.0f)
      result.setMatch(false)
    }
    result
  }

  def getWeights(out: ArrayBuffer[(Weight, Float)]): Unit = {
    out += ((this, 1.0f))
  }

  override def scorer(context: AtomicReaderContext, scoreDocsInOrder: Boolean, topScorer: Boolean, acceptDocs: Bits): Scorer = {
    val mainScorer = if (mainWeight != null) mainWeight.scorer(context, scoreDocsInOrder, topScorer, acceptDocs) else null
    val semanticScorer = if (semanticWeight != null) semanticWeight.scorer(context, scoreDocsInOrder, topScorer, acceptDocs) else null

    if (mainScorer == null) null
    else {
      val adjustedSemanticBoost = {
        val n = if (semanticScorer != null) {
          semanticScorer.getChildren().map(scorer => scorer.child.asInstanceOf[SemanticVectorScorer].getNumPayloadsUsed).foldLeft(0)(_ max _)
        } else 0
        val adjust = 1.0 / (1 + pow(1.5, 5 - n))
        query.getSemanticBoost * adjust.toFloat
      }

      new KTextScorer(this, mainScorer, semanticScorer, adjustedSemanticBoost)
    }
  }
}

class KTextScorer(weight: KTextWeight, mainScorer: Scorer, semanticScorer: Scorer, semanticBoost: Float) extends Scorer(weight) {
  private[this] var doc = if (mainScorer == null) NO_MORE_DOCS else -1
  private[this] var scoredDoc = -1
  private[this] var scoreVal = 0.0f

  private[this] val semanticScoreBase = (1.0f - semanticBoost)

  override def docID(): Int = doc

  override def nextDoc(): Int = advance(0)

  override def advance(target: Int): Int = {
    if (doc < NO_MORE_DOCS) doc = mainScorer.advance(target)
    doc
  }

  override def score(): Float = {
    if (scoredDoc < doc) {
      scoredDoc = doc
      val mainScore = mainScorer.score()
      val semScore = semanticScore()

      scoreVal = mainScore * semScore
    }
    scoreVal
  }

  @inline private[this] def semanticScore(): Float = {
    if (semanticScorer != null) {
      if (semanticScorer.docID < doc) semanticScorer.advance(doc)

      if (semanticScorer.docID == doc) {
        semanticScorer.score() * semanticBoost + semanticScoreBase
      } else {
        semanticScoreBase
      }
    } else {
      1.0f
    }
  }

  override def freq(): Int = 1

  override def cost(): Long = {
    (if (mainScorer == null) 0L else mainScorer.cost) + (if (semanticScorer == null) 0L else semanticScorer.cost)
  }
}
