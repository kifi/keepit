package com.keepit.search.sharding

import com.keepit.common.db.SequenceNumber
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.model.{ Keep, NormalizedURI }
import com.keepit.search.graph.bookmark.URIGraphIndexer
import com.keepit.search.index.Indexer
import com.keepit.shoebox.ShoeboxServiceClient
import scala.concurrent.Await
import scala.concurrent.duration._

class ShardedURIGraphIndexer(
    override val indexShards: Map[Shard[NormalizedURI], URIGraphIndexer],
    override val airbrake: AirbrakeNotifier,
    shoeboxClient: ShoeboxServiceClient) extends ShardedIndexer[NormalizedURI, Keep, URIGraphIndexer] {

  private val fetchSize = 2000

  def update(): Int = updateLock.synchronized {
    resetSequenceNumberIfReindex()

    var total = 0
    var done = false
    while (!done && !closing) {
      val bookmarks = Await.result(shoeboxClient.getBookmarksChanged(sequenceNumber, fetchSize), 180 seconds)
      done = bookmarks.isEmpty

      indexShards.foreach {
        case (shard, indexer) =>
          indexer.update(shard.indexNameSuffix, bookmarks, shard)
      }
      total += bookmarks.size
      if (!done) sequenceNumber = bookmarks.last.seq
    }
    total
  }
}
