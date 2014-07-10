package com.keepit.search.query

import com.keepit.common.logging.Logging
import com.keepit.search.semantic.SemanticVector
import com.keepit.search.PersonalizedSearcher
import com.keepit.search.Searcher
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
import org.apache.lucene.util.BytesRef
import java.util.{ Set => JSet }
import scala.math._

class SemanticVectorExtractorQuery(val semanticVectorQueries: Seq[SemanticVectorQuery], val personalQuery: Option[Query]) extends Query {
  override def createWeight(searcher: IndexSearcher): Weight = {
    new SemanticVectorExtractorWeight(
      this,
      semanticVectorQueries.map { _.createWeight(searcher) },
      personalQuery.map { _.createWeight(searcher) }
    )
  }

  override def rewrite(reader: IndexReader): Query = {
    val rewrittenPersonalQuery = personalQuery.map { _.rewrite(reader) }
    var changed: Boolean = false
    val rewrittenSemanticVectorQueries = semanticVectorQueries.map { semanticVectorQuery =>
      val rewrittenSemanticVectorQuery = semanticVectorQuery.rewrite(reader).asInstanceOf[SemanticVectorQuery]
      changed = changed || (semanticVectorQuery ne rewrittenSemanticVectorQuery)
      rewrittenSemanticVectorQuery
    }

    if ((personalQuery.orNull eq rewrittenPersonalQuery.orNull) && !changed) this
    else {
      new SemanticVectorExtractorQuery(rewrittenSemanticVectorQueries, rewrittenPersonalQuery)
    }
  }

  override def extractTerms(out: JSet[Term]): Unit = {
    semanticVectorQueries.foreach { _.extractTerms(out) }
    personalQuery.foreach { _.extractTerms(out) }
  }

  override def toString(s: String) = {
    s"SemanticVectorExtractorQuery((${semanticVectorQueries.map { _.toString(s) }.mkString(",")}), ${personalQuery.map { _.toString(s) }})"
  }

  override def equals(obj: Any): Boolean = obj match {
    case query: SemanticVectorExtractorQuery =>
      (personalQuery.equals(query.personalQuery) &&
        semanticVectorQueries.size == query.semanticVectorQueries.size &&
        (semanticVectorQueries zip query.semanticVectorQueries).forall { case (a, b) => a.equals(b) })
    case _ => false
  }

  override def hashCode(): Int = (semanticVectorQueries.hashCode() + personalQuery.hashCode())
}

class SemanticVectorExtractorWeight(query: SemanticVectorExtractorQuery, semanticWeights: Seq[Weight], personalWeight: Option[Weight]) extends Weight {

  override def getQuery() = query
  override def scoresDocsOutOfOrder() = false

  override def getValueForNormalization(): Float = {
    semanticWeights.foreach { _.getValueForNormalization() } // for side effect
    personalWeight.foreach { _.getValueForNormalization() } // for side effect
    1.0f
  }

  override def normalize(norm: Float, topLevelBoost: Float): Unit = {
    semanticWeights.foreach { _.normalize(1.0f, 1.0f) } // for side effect
    personalWeight.foreach { _.normalize(norm, topLevelBoost) }
  }

  override def explain(context: AtomicReaderContext, doc: Int): Explanation = throw new UnsupportedOperationException

  override def scorer(context: AtomicReaderContext, scoreDocsInOrder: Boolean, topScorer: Boolean, acceptDocs: Bits): Scorer = {
    val semanticScorers = semanticWeights.map { _.scorer(context, scoreDocsInOrder, topScorer, acceptDocs).asInstanceOf[SemanticVectorScorer] }
    val personalScorer = personalWeight.map { _.scorer(context, scoreDocsInOrder, topScorer, acceptDocs) }.orNull

    new SemanticVectorExtractorScorer(this, semanticScorers, personalScorer)
  }
}

class SemanticVectorExtractorScorer(weight: SemanticVectorExtractorWeight, semanticScorers: Seq[SemanticVectorScorer], personalScorer: Scorer) extends Scorer(weight) {
  private[this] var doc = -1

  override def docID(): Int = doc

  override def nextDoc(): Int = advance(0)

  override def advance(target: Int): Int = {
    if (doc < NO_MORE_DOCS) {
      doc = if (doc < target) target else doc + 1
      val docS = semanticScorers.foldLeft(NO_MORE_DOCS) { (minDoc, semanticScorer) =>
        if (semanticScorer != null) {
          val d = if (semanticScorer.docID < doc) semanticScorer.advance(doc) else semanticScorer.docID
          min(minDoc, d)
        } else {
          minDoc
        }
      }
      doc = if (doc == docS || personalScorer == null) {
        docS
      } else {
        val docP = if (personalScorer.docID < doc) personalScorer.advance(doc) else personalScorer.docID
        min(docP, docS)
      }
    }
    doc
  }

  override def score(): Float = throw new UnsupportedOperationException

  override def freq(): Int = 1

  override def cost(): Long = semanticScorers.map(_.cost()).sum

  def processSemanticVector(process: (Term, Array[Byte], Int, Int) => Unit) = {
    val isPersonalHit: Boolean = (personalScorer != null && personalScorer.docID == doc)

    semanticScorers.foreach { semanticScorer =>
      if (semanticScorer != null && semanticScorer.docID == doc) {
        val bytesRef = semanticScorer.getSemanticVectorBytesRef()
        if (bytesRef == null) {
          val defaultVector = semanticScorer.getQuerySemanticVector.bytes
          process(semanticScorer.term, defaultVector, 0, defaultVector.length)
        } else {
          process(semanticScorer.term, bytesRef.bytes, bytesRef.offset, bytesRef.length)
        }
      } else if (isPersonalHit) {
        val defaultVector = semanticScorer.getQuerySemanticVector.bytes
        process(semanticScorer.term, defaultVector, 0, defaultVector.length)
      }
    }
  }
}

