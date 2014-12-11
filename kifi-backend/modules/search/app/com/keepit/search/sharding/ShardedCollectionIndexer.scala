package com.keepit.search.sharding

import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.model.Collection
import com.keepit.model.NormalizedURI
import com.keepit.search.index.graph.collection.CollectionIndexer
import com.keepit.shoebox.ShoeboxServiceClient

class ShardedCollectionIndexer(
    override val indexShards: Map[Shard[NormalizedURI], CollectionIndexer],
    override val airbrake: AirbrakeNotifier,
    shoeboxClient: ShoeboxServiceClient) extends ShardedIndexer[NormalizedURI, Collection, CollectionIndexer] {

  private val fetchSize = 2000

  def update(): Int = updateLock.synchronized {
    resetSequenceNumberIfReindex()

    var total = 0
    var done = false
    while (!done && !closing) {
      val data = CollectionIndexer.fetchData(sequenceNumber, fetchSize, shoeboxClient)
      done = data.isEmpty

      indexShards.foreach {
        case (shard, indexer) =>
          indexer.update(shard.indexNameSuffix, data, shard)
      }
      total += data.size
      if (!done) sequenceNumber = data.last._1.seq
    }
    total
  }
}
