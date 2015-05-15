package com.keepit.search.engine

import com.keepit.search.SearchConfig
import com.keepit.search.index.Searcher
import com.keepit.search.index.graph.keep.KeepFields
import com.keepit.search.engine.query.{ RecencyScorer, RecencyQuery }
import org.apache.lucene.index.AtomicReaderContext
import org.apache.lucene.search.{ Weight, MatchAllDocsQuery }
import org.apache.lucene.util.Bits.MatchAllBits

trait KeepRecencyEvaluator { self: DebugOption =>

  protected val searcher: Searcher
  protected val config: SearchConfig

  private[this] lazy val recencyQuery = {
    val recencyBoostStrength = config.asFloat("recencyBoost")
    val halfDecayMillis = config.asFloat("halfDecayHours") * (60.0f * 60.0f * 1000.0f) // hours to millis
    new RecencyQuery(new MatchAllDocsQuery(), KeepFields.keptAtField, recencyBoostStrength, halfDecayMillis)
  }

  private[this] lazy val recencyWeight: Weight = searcher.createWeight(recencyQuery)

  protected def getRecencyScorer(readerContext: AtomicReaderContext): RecencyScorer = {
    // use MatchAllBits to avoid delete check. this is safe because RecencyScorer is used passively.
    recencyWeight.scorer(readerContext, new MatchAllBits(readerContext.reader.maxDoc())).asInstanceOf[RecencyScorer]
  }

  private[this] lazy val slowDecayingRecencyQuery = {
    val recencyBoostStrength = 1.0f
    val halfDecayMillis = (90 * 24).toFloat * (60.0f * 60.0f * 1000.0f) // 90 days
    new RecencyQuery(new MatchAllDocsQuery(), KeepFields.keptAtField, recencyBoostStrength, halfDecayMillis)
  }

  private[this] lazy val slowDecayingRecencyWeight: Weight = searcher.createWeight(slowDecayingRecencyQuery)

  protected def getSlowDecayingRecencyScorer(readerContext: AtomicReaderContext): RecencyScorer = {
    // use MatchAllBits to avoid delete check. this is safe because RecencyScorer is used passively.
    slowDecayingRecencyWeight.scorer(readerContext, new MatchAllBits(readerContext.reader.maxDoc())).asInstanceOf[RecencyScorer]
  }

  @inline
  protected def getRecencyBoost(recencyScorer: RecencyScorer, docId: Int) = {
    if (recencyScorer != null) {
      if (recencyScorer.docID() < docId) {
        if (recencyScorer.advance(docId) == docId) recencyScorer.score() else 1.0f
      } else {
        if (recencyScorer.docID() == docId) recencyScorer.score() else 1.0f
      }
    } else 1.0f
  }
}
