package com.keepit.search.graph.bookmark

import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.search.ArticleStore
import com.keepit.search.index.IndexDirectory
import com.keepit.search.sharding.Shard
import com.keepit.shoebox.ShoeboxServiceClient
import org.apache.lucene.index.IndexWriterConfig
import scala.concurrent.Await
import scala.concurrent.duration._

class StandaloneURIGraphIndexer(
  indexDirectory: IndexDirectory,
  indexWriterConfig: IndexWriterConfig,
  bookmarkStore: BookmarkStore,
  airbrake: AirbrakeNotifier,
  shoeboxClient: ShoeboxServiceClient
) extends URIGraphIndexer(indexDirectory, indexWriterConfig, bookmarkStore, airbrake, shoeboxClient) {

  override def update(): Int = updateLock.synchronized {
    resetSequenceNumberIfReindex()

    var total = 0
    var done = false
    while (!done) {
      val bookmarks = Await.result(shoeboxClient.getBookmarksChanged(sequenceNumber, 1000), 180 seconds)
      done = bookmarks.isEmpty
      total += update("", bookmarks, Shard(0,1))
    }
    total
  }
}