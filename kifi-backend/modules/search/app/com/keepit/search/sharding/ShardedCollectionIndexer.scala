package com.keepit.search.sharding

import com.keepit.common.db.SequenceNumber
import com.keepit.model.Collection
import com.keepit.search.graph.collection.CollectionIndexer
import com.keepit.search.graph.collection.CollectionNameIndexer
import com.keepit.search.graph.collection.CollectionSearcher
import com.keepit.search.index.Indexer
import com.keepit.shoebox.ShoeboxServiceClient
import scala.concurrent.Await
import scala.concurrent.duration._

class ShardedCollectionIndexer(
  override val indexShards: Map[Shard, CollectionIndexer],
  shoeboxClient : ShoeboxServiceClient
) extends ShardedIndexer[CollectionIndexer] {

  private val fetchSize = 2000

  def update(): Int = updateLock.synchronized {
    resetSequenceNumberIfReindex()

    var total = 0
    var done = false
    while (!done) {
      val collections: Seq[Collection] = Await.result(shoeboxClient.getCollectionsChanged(sequenceNumber, fetchSize), 180 seconds)
      done = collections.isEmpty

      indexShards.foreach{ case (shard, indexer) =>
        indexer.update(s"CollectionIndex${shard.indexNameSuffix}", collections, shard)
      }
      total += collections.size
      if (!done) sequenceNumber = collections.last.seq
    }
    total
  }
}
