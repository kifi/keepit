package com.keepit.search.engine.uri

import com.keepit.search.engine.{ DirectScoreContext, Visibility, DebugOption, ScoreVectorSourceLike }
import com.keepit.search.engine.query.core.QueryProjector
import com.keepit.search.SearchFilter
import com.keepit.search.index.article.{ ArticleFields, ArticleVisibility }
import com.keepit.search.index.{ Searcher, WrappedSubReader }
import com.keepit.search.util.join.{ BloomFilter, DataBuffer, DataBufferWriter }
import org.apache.lucene.index.AtomicReaderContext
import org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS
import org.apache.lucene.search.{ Query, Scorer }

class UriFromArticlesScoreVectorSource(protected val searcher: Searcher, filter: SearchFilter) extends ScoreVectorSourceLike {

  override protected def preprocess(query: Query): Query = QueryProjector.project(query, ArticleFields.textSearchFields)

  protected def writeScoreVectors(readerContext: AtomicReaderContext, scorers: Array[Scorer], coreSize: Int, output: DataBuffer, directScoreContext: DirectScoreContext): Unit = {
    val reader = readerContext.reader.asInstanceOf[WrappedSubReader]
    val idFilter = filter.idFilter

    val pq = createScorerQueue(scorers, coreSize)
    if (pq.size <= 0) return // no scorer

    directScoreContext.setScorerQueue(pq)

    val bloomFilter = if ((debugFlags & DebugOption.NoDirectPath.flag) != 0) {
      BloomFilter.full // this disables the direct path.
    } else {
      BloomFilter(output) // a bloom filter which test if a uri id is in the buffer
    }

    val articleVisibility = ArticleVisibility(reader)

    val idMapper = reader.getIdMapper
    val writer: DataBufferWriter = new DataBufferWriter

    val taggedScores = pq.createScoreArray // tagged floats

    var docId = pq.top.doc
    while (docId < NO_MORE_DOCS) {
      val uriId = idMapper.getId(docId)

      if (idFilter.findIndex(uriId) < 0) { // use findIndex to avoid boxing
        // An article hit may or may not be visible according to the restriction
        val visibility = if (articleVisibility.isVisible(docId)) Visibility.OTHERS else Visibility.RESTRICTED

        if (bloomFilter(uriId)) {
          // get all scores and write to the buffer
          val size = pq.getTaggedScores(taggedScores)
          output.alloc(writer, visibility, 8 + size * 4) // id (8 bytes) and taggedFloats (size * 4 bytes)
          writer.putLong(uriId).putTaggedFloatBits(taggedScores, size)

          docId = pq.top.doc // next doc
        } else {
          if (visibility != Visibility.RESTRICTED) {
            // this uriId is not in the buffer
            // it is safe to bypass the buffering and joining (assuming all score vector sources other than this are executed already)
            // write directly to the collector through directScoreContext
            directScoreContext.put(uriId, visibility)

            docId = pq.top.doc // next doc
          } else {
            docId = pq.skipCurrentDoc() // skip this doc
          }
        }
      } else {
        docId = pq.skipCurrentDoc() // skip this doc
      }
    }
  }
}
