package com.keepit.search.query

import com.keepit.common.logging.Logging
import org.apache.lucene.index.AtomicReaderContext
import org.apache.lucene.index.IndexReader
import org.apache.lucene.index.Term
import org.apache.lucene.search.ComplexExplanation
import org.apache.lucene.search.DisjunctionMaxQuery
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import org.apache.lucene.search.Explanation
import org.apache.lucene.search.Query
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.Scorer
import org.apache.lucene.search.Weight
import org.apache.lucene.util.Bits
import java.util.{ Set => JSet }
import scala.collection.mutable.ArrayBuffer
import scala.math._
import collection.JavaConversions._

object TextQuery {
  val personalQueryTieBreakerMultiplier = 0.5f
  val regularQueryTieBreakerMultiplier = 0.5f
}

class TextQuery extends Query with Logging {
  import TextQuery._

  private var personalQuery: Query = new DisjunctionMaxQuery(personalQueryTieBreakerMultiplier)
  private var regularQuery: Query = new DisjunctionMaxQuery(regularQueryTieBreakerMultiplier)

  var terms: Array[Term] = Array()
  var stems: Array[Term] = Array()

  val concatStems: ArrayBuffer[String] = ArrayBuffer()

  private[this] var totalSubQueryCnt: Int = 0

  def addPersonalQuery(query: Query, boost: Float = 1.0f): Unit = {
    query.setBoost(boost)
    personalQuery = personalQuery match {
      case disjunct: DisjunctionMaxQuery =>
        totalSubQueryCnt += 1
        disjunct.add(query)
        disjunct
      case _ =>
        log.error("TextQuery: DisjunctionMaxQuery match failed")
        throw new Exception("Failed to add personal query")
    }
  }

  def addRegularQuery(query: Query, boost: Float = 1.0f): Unit = {
    query.setBoost(boost)
    regularQuery = regularQuery match {
      case disjunct: DisjunctionMaxQuery =>
        totalSubQueryCnt += 1
        disjunct.add(query)
        disjunct
      case _ =>
        log.error("TextQuery: DisjunctionMaxQuery match failed")
        throw new Exception("Failed to add regular query")
    }
  }

  private[this] var collectionIds: Set[Long] = Set()

  def addCollectionQuery(collectionId: Long, boost: Float = 1.0f): Unit = {
    if (!collectionIds.contains(collectionId)) {
      collectionIds += collectionId
      addPersonalQuery(new CollectionQuery(collectionId), boost)
    }
  }

  override def createWeight(searcher: IndexSearcher): Weight = {
    new TextWeight(
      this,
      personalQuery.createWeight(searcher),
      regularQuery.createWeight(searcher))
  }

  override def rewrite(reader: IndexReader): Query = {
    val rewrittenPersonalQuery = personalQuery.rewrite(reader)
    val rewrittenRegularQuery = regularQuery.rewrite(reader)
    if ((personalQuery eq rewrittenPersonalQuery) && (regularQuery eq rewrittenRegularQuery)) this
    else {
      val rewritten = this.clone().asInstanceOf[TextQuery]
      rewritten.personalQuery = rewrittenPersonalQuery
      rewritten.regularQuery = rewrittenRegularQuery
      rewritten
    }
  }

  override def extractTerms(out: JSet[Term]): Unit = {
    personalQuery.extractTerms(out)
    regularQuery.extractTerms(out)
  }

  override def toString(s: String) = {
    s"TextQuery(${personalQuery.toString(s)} ${regularQuery.toString(s)})"
  }

  override def equals(obj: Any): Boolean = obj match {
    case query: TextQuery => (personalQuery.equals(query.personalQuery) && regularQuery.equals(query.regularQuery))
    case _ => false
  }

  override def hashCode(): Int = personalQuery.hashCode() + regularQuery.hashCode()

  def isEmpty: Boolean = { totalSubQueryCnt == 0 }
}

class TextWeight(
    query: TextQuery,
    personalWeight: Weight,
    regularWeight: Weight) extends Weight with Logging {

  override def getQuery() = query
  override def scoresDocsOutOfOrder() = false

  override def getValueForNormalization(): Float = {
    val psub = if (personalWeight != null) personalWeight.getValueForNormalization() else 1.0f
    val rsub = if (regularWeight != null) regularWeight.getValueForNormalization() else 1.0f
    val maxVal = max(psub, rsub)
    val boost = query.getBoost()

    (maxVal * boost * boost)
  }

  override def normalize(norm: Float, topLevelBoost: Float): Unit = {
    val boost = query.getBoost
    if (personalWeight != null) personalWeight.normalize(norm, topLevelBoost * boost)
    if (regularWeight != null) regularWeight.normalize(norm, topLevelBoost * boost)
  }

  override def explain(context: AtomicReaderContext, doc: Int): Explanation = {
    val reader = context.reader
    val sc = scorer(context, true, false, reader.getLiveDocs);
    val exists = (sc != null && sc.advance(doc) == doc);

    val result = new ComplexExplanation()
    if (exists) {
      result.setDescription("TextQuery")
      result.setValue(sc.score)
      result.setMatch(true)
      if (personalWeight != null) {
        val exp = personalWeight.explain(context, doc)
        if (exp.getValue() > 0.0f) result.addDetail(personalWeight.explain(context, doc))
      }
      if (regularWeight != null) {
        val exp = regularWeight.explain(context, doc)
        if (exp.getValue() > 0.0f) result.addDetail(regularWeight.explain(context, doc))
      }
    } else {
      result.setDescription("TextQuery, doesn't match id %d".format(doc))
      result.setValue(0.0f)
      result.setMatch(false)
    }
    result
  }

  override def scorer(context: AtomicReaderContext, scoreDocsInOrder: Boolean, topScorer: Boolean, acceptDocs: Bits): Scorer = {
    val personalScorer = if (personalWeight != null) personalWeight.scorer(context, scoreDocsInOrder, topScorer, acceptDocs) else null
    val regularScorer = if (regularWeight != null) regularWeight.scorer(context, scoreDocsInOrder, topScorer, acceptDocs) else null

    if (personalScorer == null && regularScorer == null) null
    else {
      new TextScorer(this, personalScorer, regularScorer)
    }
  }
}

class TextScorer(weight: TextWeight, personalScorer: Scorer, regularScorer: Scorer) extends Scorer(weight) {
  private[this] var doc = -1
  private[this] var docP = if (personalScorer == null) NO_MORE_DOCS else -1
  private[this] var docR = if (regularScorer == null) NO_MORE_DOCS else -1
  private[this] var scoredDoc = -1
  private[this] var scoreVal = 0.0f

  override def docID(): Int = doc

  override def nextDoc(): Int = advance(0)

  override def advance(target: Int): Int = {
    if (doc < NO_MORE_DOCS) {
      doc = if (doc < target) target else doc + 1
      if (docP < doc) docP = personalScorer.advance(doc)
      if (docR < doc) docR = regularScorer.advance(doc)
      doc = min(docP, docR)
    }
    doc
  }

  override def score(): Float = {
    if (scoredDoc < doc) {
      scoredDoc = doc
      val scoreP = if (docP == doc) personalScorer.score() else 0.0f
      val scoreR = if (docR == doc) regularScorer.score() else 0.0f
      scoreVal = max(scoreP, scoreR)
    }
    scoreVal
  }

  override def freq(): Int = 1

  override def cost(): Long = {
    (if (personalScorer == null) 0L else personalScorer.cost)
    +(if (regularScorer == null) 0L else regularScorer.cost)
  }
}

