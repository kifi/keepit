package com.keepit.search.sharding

import com.keepit.common.db.SequenceNumber
import com.keepit.search.ArticleStore
import com.keepit.search.index.ArticleIndexer
import com.keepit.search.index.Indexer
import com.keepit.shoebox.ShoeboxServiceClient
import scala.concurrent.Await
import scala.concurrent.duration._

class ShardedArticleIndexer(
  override val indexShards: Map[Shard, ArticleIndexer],
  articleStore: ArticleStore,
  shoeboxClient : ShoeboxServiceClient
) extends ShardedIndexer[ArticleIndexer] {

  private val fetchSize = 2000

  def update(): Int = updateLock.synchronized {
    resetSequenceNumberIfReindex()

    var total = 0
    var done = false
    while (!done) {
      val uris = Await.result(shoeboxClient.getIndexableUris(sequenceNumber.value, fetchSize), 180 seconds)
      done = uris.isEmpty

      indexShards.foldLeft(uris){ case (toBeIndexed, (shard, indexer)) =>
        val (next, rest) = toBeIndexed.partition{ uri => shard.contains(uri.id.get) }
        total += indexer.doUpdate("ArticleIndex${shard.indexNameSuffix}"){
          next.iterator.map(indexer.buildIndexable)
        }
        rest
      }
    }
    total
  }
}
