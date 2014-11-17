package com.keepit.search.engine.explain

import org.apache.lucene.index.AtomicReaderContext
import org.apache.lucene.search.{ DocIdSetIterator, Weight, Scorer }

object TargetedScorer {

  def apply(readerContext: AtomicReaderContext, weight: Weight, targetId: Long, resolver: Resolver) = {
    val subScorer = weight.scorer(readerContext, readerContext.reader.getLiveDocs)
    if (subScorer != null) new TargetedScorer(weight, subScorer, targetId, resolver) else null
  }

  trait Resolver {
    def apply(docId: Int): Long
  }
}

class TargetedScorer(weight: Weight, subScorer: Scorer, targetId: Long, resolver: TargetedScorer.Resolver) extends Scorer(weight) {

  override def docID(): Int = subScorer.docID()

  override def nextDoc(): Int = {
    var doc = subScorer.nextDoc()
    while (doc < DocIdSetIterator.NO_MORE_DOCS) {
      if (resolver(doc) == targetId) return doc
      doc = subScorer.nextDoc()
    }
    doc
  }

  override def advance(target: Int): Int = {
    var doc = subScorer.advance(target)
    if (resolver(doc) == targetId) return doc
    else nextDoc()
  }

  override def score(): Float = subScorer.score()

  override def freq(): Int = subScorer.freq()

  override def cost(): Long = subScorer.cost()
}
