package com.keepit.search.engine

import com.keepit.search.SearchConfig
import com.keepit.search.graph.keep.KeepFields
import com.keepit.search.query.{ RecencyScorer, RecencyQuery }
import org.apache.lucene.index.AtomicReaderContext
import org.apache.lucene.search.{ Weight, MatchAllDocsQuery }
import org.apache.lucene.util.Bits.MatchAllBits

trait KeepRecencyEvaluator { self: ScoreVectorSourceLike =>

  protected val config: SearchConfig

  private[this] lazy val recencyQuery = {
    val recencyBoostStrength = config.asFloat("recencyBoost")
    val halfDecayMillis = config.asFloat("halfDecayHours") * (60.0f * 60.0f * 1000.0f) // hours to millis
    new RecencyQuery(new MatchAllDocsQuery(), KeepFields.createdAtField, recencyBoostStrength, halfDecayMillis)
  }

  private[this] lazy val recencyWeight: Weight = searcher.createWeight(recencyQuery)

  protected def getRecencyScorer(readerContext: AtomicReaderContext): RecencyScorer = {
    // use MatchAllBits to avoid delete check. this is safe because RecencyScorer is used passively.
    val scorer = recencyWeight.scorer(readerContext, true, false, new MatchAllBits(readerContext.reader.maxDoc())).asInstanceOf[RecencyScorer]
    if (scorer == null) log.warn("RecencyScorer is null")
    scorer
  }

  private[this] lazy val slowDecayingRecencyQuery = {
    val recencyBoostStrength = 1.0f
    val halfDecayMillis = (90 * 24).toFloat * (60.0f * 60.0f * 1000.0f) // 90 days
    new RecencyQuery(new MatchAllDocsQuery(), KeepFields.createdAtField, recencyBoostStrength, halfDecayMillis)
  }

  private[this] lazy val slowDecayingRecencyWeight: Weight = searcher.createWeight(slowDecayingRecencyQuery)

  protected def getSlowDecayingRecencyScorer(readerContext: AtomicReaderContext): RecencyScorer = {
    // use MatchAllBits to avoid delete check. this is safe because RecencyScorer is used passively.
    val scorer = slowDecayingRecencyWeight.scorer(readerContext, true, false, new MatchAllBits(readerContext.reader.maxDoc())).asInstanceOf[RecencyScorer]
    if (scorer == null) log.warn("SlowDecayingRecencyScorer is null")
    scorer
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
