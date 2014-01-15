package com.keepit.search.article

import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.search.ArticleStore
import com.keepit.search.index.IndexDirectory
import com.keepit.search.sharding.Shard
import com.keepit.shoebox.ShoeboxServiceClient
import org.apache.lucene.index.IndexWriterConfig
import scala.concurrent.Await
import scala.concurrent.duration._

class StandaloneArticleIndexer(
  indexDirectory: IndexDirectory,
  indexWriterConfig: IndexWriterConfig,
  articleStore: ArticleStore,
  airbrake: AirbrakeNotifier,
  shoeboxClient: ShoeboxServiceClient
) extends ArticleIndexer(indexDirectory, indexWriterConfig, articleStore, airbrake, shoeboxClient) {

  override def update(): Int = updateLock.synchronized {
    resetSequenceNumberIfReindex()

    var total = 0
    var done = false
    while (!done) {
      val uris = Await.result(shoeboxClient.getIndexableUris(sequenceNumber.value, 1000), 180 seconds)
      done = uris.isEmpty
      total += update("", uris, Shard(0,1))
    }
    total
  }

  def update(fsize: Int): Int = updateLock.synchronized {
    resetSequenceNumberIfReindex()

    doUpdate("ArticleIndex") {
      val uris = Await.result(shoeboxClient.getIndexableUris(sequenceNumber.value, fsize), 180 seconds)
      uris.iterator.map(buildIndexable)
    }
  }
}