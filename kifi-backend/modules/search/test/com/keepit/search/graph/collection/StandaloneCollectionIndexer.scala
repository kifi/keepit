package com.keepit.search.graph.collection

import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.search.ArticleStore
import com.keepit.search.index.IndexDirectory
import com.keepit.search.sharding.Shard
import com.keepit.shoebox.ShoeboxServiceClient
import org.apache.lucene.index.IndexWriterConfig
import scala.concurrent.Await
import scala.concurrent.duration._
import com.keepit.model.Collection

class StandaloneCollectionIndexer(
  indexDirectory: IndexDirectory,
  indexWriterConfig: IndexWriterConfig,
  collectionNameIndexer: CollectionNameIndexer,
  airbrake: AirbrakeNotifier,
  shoeboxClient: ShoeboxServiceClient
) extends CollectionIndexer(indexDirectory, indexWriterConfig, collectionNameIndexer, airbrake, shoeboxClient) {

  override def update(): Int = updateLock.synchronized {
    resetSequenceNumberIfReindex()

    var total = 0
    var done = false
    while (!done) {
      val collections: Seq[Collection] = Await.result(shoeboxClient.getCollectionsChanged(sequenceNumber, 1000), 180 seconds)
      done = collections.isEmpty
      total += update("CollectionIndex", collections, Shard(0, 1))
    }
    total
  }
}