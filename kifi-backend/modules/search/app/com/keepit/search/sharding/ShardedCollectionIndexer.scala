package com.keepit.search.sharding

import com.keepit.common.db.SequenceNumber
import com.keepit.model.Collection
import com.keepit.model.NormalizedURI
import com.keepit.search.graph.collection.CollectionIndexer
import com.keepit.search.graph.collection.CollectionNameIndexer
import com.keepit.search.graph.collection.CollectionSearcher
import com.keepit.search.index.Indexer
import com.keepit.shoebox.ShoeboxServiceClient
import scala.concurrent.Await
import scala.concurrent.duration._

class ShardedCollectionIndexer(
  override val indexShards: Map[Shard[NormalizedURI], CollectionIndexer],
  shoeboxClient : ShoeboxServiceClient
) extends ShardedIndexer[NormalizedURI, CollectionIndexer] {

  private val fetchSize = 2000

  def update(): Int = updateLock.synchronized {
    resetSequenceNumberIfReindex()

    var total = 0
    var done = false
    while (!done && !closing) {
      val data = CollectionIndexer.fetchData(sequenceNumber, fetchSize, shoeboxClient)
      done = data.isEmpty

      indexShards.foreach{ case (shard, indexer) =>
        indexer.update(shard.indexNameSuffix, data, shard)
      }
      total += data.size
      if (!done) sequenceNumber = data.last._1.seq
    }
    total
  }
}
