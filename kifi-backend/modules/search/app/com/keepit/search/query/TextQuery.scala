package com.keepit.search.query

import com.keepit.common.logging.Logging
import com.keepit.search.index.PersonalizedSearcher
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
import java.util.{Set => JSet}
import scala.collection.mutable.ArrayBuffer
import scala.math._


class TextQuery extends Query {

  private var personalQuery: Query = new DisjunctionMaxQuery(0.3f)
  private var regularQuery: Query = new DisjunctionMaxQuery(0.3f)
  private var semanticVectorQuery: Query = new DisjunctionMaxQuery(0.0f)

  var terms: Array[Term] = Array()
  var stems: Array[Term] = Array()

  val concatStems: ArrayBuffer[String] = ArrayBuffer()

  private[this] var totalSubQueryCnt: Int = 0

  def addPersonalQuery(query: Query, boost: Float = 1.0f): Unit = {
    query.setBoost(boost)
    personalQuery = personalQuery match {
      case disjunct: DisjunctionMaxQuery =>
        disjunct.add(query)
        disjunct
      case _ => {
        val disjunct = new DisjunctionMaxQuery(0.3f)
        disjunct.add(personalQuery)
        disjunct.add(query)
        disjunct
      }
    }
    totalSubQueryCnt += 1
  }

  def addRegularQuery(query: Query, boost: Float = 1.0f): Unit = {
    query.setBoost(boost)
    regularQuery = regularQuery match {
      case disjunct: DisjunctionMaxQuery =>
        disjunct.add(query)
        disjunct
      case _ => {
        val disjunct = new DisjunctionMaxQuery(0.3f)
        disjunct.add(personalQuery)
        disjunct.add(query)
        disjunct
      }
    }
    totalSubQueryCnt += 1
  }

  private[this] var collectionIds: Set[Long] = Set()

  def addCollectionQuery(collectionId: Long, boost: Float = 1.0f): Unit = {
    if (!collectionIds.contains(collectionId)) {
      collectionIds += collectionId
      addPersonalQuery(new CollectionQuery(collectionId), boost)
    }
  }

  def getSemanticVectorExtractorQuery(): SemanticVectorExtractorQuery = {
    val semanticVectorQueries: Seq[SemanticVectorQuery] = semanticVectorQuery match {
      case disjunct: DisjunctionMaxQuery =>
        val buf = new ArrayBuffer[SemanticVectorQuery]
        val iter = disjunct.iterator
        while (iter.hasNext) buf += iter.next().asInstanceOf[SemanticVectorQuery]
        buf
      case semantic: SemanticVectorQuery =>
        Seq[SemanticVectorQuery](semantic)
    }
    new SemanticVectorExtractorQuery(semanticVectorQueries, Option(personalQuery))
  }

  private[this] var semanticBoost = 0.0f

  def setSemanticBoost(boost: Float): Unit = { semanticBoost = boost }

  def getSemanticBoost(): Float = semanticBoost

  def addSemanticVectorQuery(field: String, text: String): Unit = {
    val query = SemanticVectorQuery(new Term(field, text))
    semanticVectorQuery = semanticVectorQuery match {
      case disjunct: DisjunctionMaxQuery =>
        disjunct.add(query)
        disjunct
      case _ => {
        val disjunct = new DisjunctionMaxQuery(0.0f)
        disjunct.add(personalQuery)
        disjunct.add(query)
        disjunct
      }
    }
    totalSubQueryCnt += 1
  }

  override def createWeight(searcher: IndexSearcher): Weight = {
    new TextWeight(
      this,
      personalQuery.createWeight(searcher),
      regularQuery.createWeight(searcher),
      if (semanticBoost > 0.0f) semanticVectorQuery.createWeight(searcher) else null)
  }

  override def rewrite(reader: IndexReader): Query = {
    val rewrittenPersonalQuery = personalQuery.rewrite(reader)
    val rewrittenRegularQuery = regularQuery.rewrite(reader)
    val rewrittenSemanticVectorQuery = semanticVectorQuery.rewrite(reader)
    if ((personalQuery eq rewrittenPersonalQuery) && (regularQuery eq rewrittenRegularQuery) && (semanticVectorQuery eq rewrittenSemanticVectorQuery)) this
    else {
      val rewritten = this.clone().asInstanceOf[TextQuery]
      rewritten.personalQuery = rewrittenPersonalQuery
      rewritten.regularQuery = rewrittenRegularQuery
      rewritten.semanticVectorQuery = rewrittenSemanticVectorQuery
      rewritten
    }
  }

  override def extractTerms(out: JSet[Term]): Unit = {
    personalQuery.extractTerms(out)
    regularQuery.extractTerms(out)
    semanticVectorQuery.extractTerms(out)
  }

  override def toString(s: String) = {
    s"TextQuery(${personalQuery.toString(s)} ${regularQuery.toString(s)} ${semanticVectorQuery.toString(s)})"
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
  regularWeight: Weight,
  semanticWeight: Weight) extends Weight with Logging {

  private[this] val tieBreakerMultiplier = 0.3f

  override def getQuery() = query
  override def scoresDocsOutOfOrder() = false

  override def getValueForNormalization(): Float = {
    if (semanticWeight != null) semanticWeight.getValueForNormalization() // for side effect

    val psub = if (personalWeight != null) personalWeight.getValueForNormalization() else 1.0f
    val rsub = if (regularWeight != null) regularWeight.getValueForNormalization() else 1.0f
    val sumVal = psub + rsub
    val maxVal = max(psub, rsub)
    val boost = query.getBoost()
    (((sumVal - maxVal) * tieBreakerMultiplier * tieBreakerMultiplier) + maxVal) * boost * boost
  }

  override def normalize(norm: Float, topLevelBoost: Float): Unit = {
    if (semanticWeight != null) semanticWeight.normalize(1.0f, 1.0f) // for side effect

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
      if (personalWeight != null) result.addDetail(personalWeight.explain(context, doc))
      if (regularWeight != null) result.addDetail(regularWeight.explain(context, doc))
    } else {
      result.setDescription("TextQuery, doesn't match id %d".format(doc))
      result.setValue(0)
      result.setMatch(false)
    }
    result
  }


  override def scorer(context: AtomicReaderContext, scoreDocsInOrder: Boolean, topScorer: Boolean, acceptDocs: Bits): Scorer = {
    val personalScorer = if (personalWeight != null) personalWeight.scorer(context, scoreDocsInOrder, topScorer, acceptDocs) else null
    val regularScorer = if (regularWeight != null) regularWeight.scorer(context, scoreDocsInOrder, topScorer, acceptDocs) else null
    val semanticScorer = if (semanticWeight != null) semanticWeight.scorer(context, scoreDocsInOrder, topScorer, acceptDocs) else null

    if (personalScorer == null && regularScorer == null) null
    else new TextScorer(this, personalScorer, regularScorer, semanticScorer, query.getSemanticBoost, tieBreakerMultiplier)
  }
}

class TextScorer(weight: TextWeight, personalScorer: Scorer, regularScorer: Scorer, semanticScorer: Scorer, semanticBoost: Float, tieBreakerMultiplier: Float) extends Scorer(weight) {
  private[this] var doc = -1
  private[this] var docP = if (personalScorer == null) NO_MORE_DOCS else -1
  private[this] var docR = if (regularScorer == null) NO_MORE_DOCS else -1
  private[this] var scoredDoc = -1
  private[this] var scoreVal = 0.0f
  private[this] val semanticScoreBase = (1.0f - semanticBoost)

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
      val scoreMax = max(scoreP, scoreR)
      val scoreSum = scoreP + scoreR
      val semScore = semanticScore()

      scoreVal = (scoreMax + (scoreSum - scoreMax) * tieBreakerMultiplier) * semScore
    }
    scoreVal
  }

  @inline private[this] def semanticScore(): Float = {
    if (semanticScorer != null) {
      if (semanticScorer.docID < doc) semanticScorer.advance(doc)

      if (semanticScorer.docID == doc) {
        semanticScorer.score() * semanticBoost + semanticScoreBase
      } else {
        if (docP == doc) {
          0.8f * semanticBoost + semanticScoreBase
        } else {
          semanticScoreBase
        }
      }
    } else {
      1.0f
    }
  }

  override def freq() = 1
}

