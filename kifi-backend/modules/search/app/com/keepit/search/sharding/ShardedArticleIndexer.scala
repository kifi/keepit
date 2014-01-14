package com.keepit.search.sharding

import com.keepit.model.NormalizedURI
import com.keepit.search.ArticleStore
import com.keepit.search.article.ArticleIndexer
import com.keepit.shoebox.ShoeboxServiceClient
import scala.concurrent.Await
import scala.concurrent.duration._

class ShardedArticleIndexer(
  override val indexShards: Map[Shard[NormalizedURI], ArticleIndexer],
  articleStore: ArticleStore,
  shoeboxClient : ShoeboxServiceClient
) extends ShardedIndexer[NormalizedURI, ArticleIndexer] {

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
        total += indexer.update(shard.indexNameSuffix, next, shard)
        rest
      }
      if (!done) sequenceNumber = uris.last.seq
    }
    total
  }
}
