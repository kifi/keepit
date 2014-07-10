package com.keepit.search.query

import com.keepit.common.db.Id
import com.keepit.common.logging.Logging
import com.keepit.model.Collection
import com.keepit.search.graph.collection.CollectionToUriEdgeSet
import com.keepit.search.PersonalizedSearcher
import org.apache.lucene.index.AtomicReaderContext
import org.apache.lucene.index.IndexReader
import org.apache.lucene.index.Term
import org.apache.lucene.search.ComplexExplanation
import org.apache.lucene.search.DocIdSetIterator
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.Query
import org.apache.lucene.search.Scorer
import org.apache.lucene.search.Weight
import org.apache.lucene.search.similarities.TFIDFSimilarity
import org.apache.lucene.util.Bits
import java.util.{ Set => JSet }

class CollectionQuery(val collectionId: Long) extends Query {
  override def createWeight(searcher: IndexSearcher): Weight = new CollectionWeight(this, searcher.asInstanceOf[PersonalizedSearcher])

  override def rewrite(reader: IndexReader): Query = this

  override def extractTerms(out: JSet[Term]): Unit = {}

  override def toString(s: String) = s"Collection(${collectionId})"

  override def equals(obj: Any): Boolean = obj match {
    case query: CollectionQuery => (collectionId == query.collectionId)
    case _ => false
  }

  override def hashCode(): Int = toString("").hashCode()
}

class CollectionWeight(query: CollectionQuery, searcher: PersonalizedSearcher) extends Weight with Logging {
  val idSetFilter = {
    val edgeSet = searcher.collectionSearcher.getCollectionToUriEdgeSet(Id[Collection](query.collectionId))
    new IdSetFilter(edgeSet.destIdLongSet)
  }

  private[this] val idf: Float = searcher.getSimilarity.asInstanceOf[TFIDFSimilarity].idf(idSetFilter.ids.size, searcher.indexReader.maxDoc)

  override def getQuery() = query
  override def scoresDocsOutOfOrder() = false

  override def getValueForNormalization() = {
    val value = query.getBoost() * idf
    (value * value)
  }

  private[this] var value = 0.0f

  override def normalize(norm: Float, topLevelBoost: Float) {
    value = norm * topLevelBoost * query.getBoost * idf * idf
  }

  override def explain(context: AtomicReaderContext, doc: Int) = {
    val collectionName = searcher.collectionSearcher.getName(query.collectionId)

    val reader = context.reader
    val sc = scorer(context, true, false, reader.getLiveDocs);
    val exists = (sc != null && sc.advance(doc) == doc);

    val result = new ComplexExplanation()
    if (exists) {
      result.setDescription(s"collection (${collectionName})")
      result.setValue(sc.score)
      result.setMatch(true)
    } else {
      result.setDescription(s"collection (${collectionName}), doesn't match id %d".format(doc))
      result.setValue(0)
      result.setMatch(false)
    }
    result
  }

  override def scorer(context: AtomicReaderContext, scoreDocsInOrder: Boolean, topScorer: Boolean, acceptDocs: Bits): Scorer = {
    new CollectionScorer(this, idSetFilter.getDocIdSet(context, acceptDocs).iterator(), value)
  }
}

class CollectionScorer(weight: CollectionWeight, iterator: DocIdSetIterator, scoreValue: Float) extends Scorer(weight) {
  override def docID(): Int = iterator.docID()
  override def nextDoc(): Int = iterator.nextDoc()
  override def advance(target: Int): Int = iterator.advance(target)
  override def score(): Float = scoreValue
  override def freq(): Int = 1
  override def cost(): Long = iterator.cost()
}

