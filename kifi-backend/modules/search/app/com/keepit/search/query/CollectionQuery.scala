package com.keepit.search.query

import com.keepit.common.db.Id
import com.keepit.common.logging.Logging
import com.keepit.model.Collection
import com.keepit.search.graph.CollectionToUriEdgeSet
import com.keepit.search.index.PersonalizedSearcher
import org.apache.lucene.index.AtomicReaderContext
import org.apache.lucene.index.IndexReader
import org.apache.lucene.index.Term
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.Query
import org.apache.lucene.search.Weight
import org.apache.lucene.search.ComplexExplanation
import org.apache.lucene.search.Scorer
import org.apache.lucene.search.DocIdSetIterator
import org.apache.lucene.util.Bits
import java.util.{Set => JSet}

class CollectionQuery(val collectionId: Id[Collection]) extends Query {
  override def createWeight(searcher: IndexSearcher): Weight = new CollectionWeight(this, searcher.asInstanceOf[PersonalizedSearcher])

  override def rewrite(reader: IndexReader): Query = this

  override def extractTerms(out: JSet[Term]): Unit = { }

  override def toString(s: String) = s"Collection(${collectionId})"

  override def equals(obj: Any): Boolean = obj match {
    case query: CollectionQuery => (collectionId == query.collectionId)
    case _ => false
  }

  override def hashCode(): Int = toString("").hashCode()
}

class CollectionWeight(query: CollectionQuery, searcher: PersonalizedSearcher) extends Weight with Logging {
  val idSetFilter = {
    val edgeSet = searcher.collectionSearcher.getCollectionToUriEdgeSet(query.collectionId)
    new IdSetFilter(edgeSet.destIdLongSet)
  }

  override def getQuery() = query
  override def scoresDocsOutOfOrder() = false

  override def getValueForNormalization() = {
    val value = query.getBoost()
    (value * value)
  }

  private[this] var value = 0.0f

  override def normalize(norm: Float, topLevelBoost: Float) {
    value = query.getBoost * norm * topLevelBoost
  }

  override def explain(context: AtomicReaderContext, doc: Int) = {
    val collectionName = searcher.collectionSearcher.getName(query.collectionId)

    val reader = context.reader
    val sc = scorer(context, true, false, reader.getLiveDocs);
    val exists = (sc != null && sc.advance(doc) == doc);

    val result = new ComplexExplanation()
    if (exists) {
      result.setDescription("collection (${collectionName})")
      result.setValue(sc.score)
      result.setMatch(true)
    } else {
      result.setDescription("collection (${collectionName}), doesn't match id %d".format(doc))
      result.setValue(0)
      result.setMatch(false)
    }
    result
  }

  override def scorer(context: AtomicReaderContext, scoreDocsInOrder: Boolean, topScorer: Boolean, acceptDocs: Bits): Scorer = {
    new CollectionScorer(query, this, idSetFilter.getDocIdSet(context, acceptDocs).iterator(), value)
  }
}

class CollectionScorer(query: CollectionQuery, weight: CollectionWeight, iterator: DocIdSetIterator, scoreValue: Float) extends Scorer(weight) {
  override def docID(): Int = iterator.nextDoc()
  override def nextDoc(): Int = iterator.nextDoc()
  override def advance(target: Int): Int = iterator.advance(target)
  override def score() = scoreValue
  override def freq() = 1
}

