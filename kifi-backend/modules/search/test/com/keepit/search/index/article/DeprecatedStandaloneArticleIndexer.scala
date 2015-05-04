package com.keepit.search.index.article

import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.search.ArticleStore
import com.keepit.search.index.IndexDirectory
import com.keepit.search.index.sharding.Shard
import com.keepit.shoebox.ShoeboxServiceClient
import scala.concurrent.Await
import scala.concurrent.duration._

class DeprecatedStandaloneArticleIndexer(
    indexDirectory: IndexDirectory,
    articleStore: ArticleStore,
    airbrake: AirbrakeNotifier,
    shoeboxClient: ShoeboxServiceClient) extends DeprecatedArticleIndexer(indexDirectory, articleStore, airbrake) {

  override def update(): Int = updateLock.synchronized {
    resetSequenceNumberIfReindex()

    var total = 0
    var done = false
    while (!done) {
      val uris = Await.result(shoeboxClient.getIndexableUris(sequenceNumber, 1000), 180 seconds)
      done = uris.isEmpty
      total += update("", uris, Shard(0, 1))
    }
    total
  }

  def update(fsize: Int): Int = updateLock.synchronized {
    resetSequenceNumberIfReindex()

    doUpdate("ArticleIndex") {
      val uris = Await.result(shoeboxClient.getIndexableUris(sequenceNumber, fsize), 180 seconds)
      uris.iterator.map(buildIndexable)
    }
  }
}