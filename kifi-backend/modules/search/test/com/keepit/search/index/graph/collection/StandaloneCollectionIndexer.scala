package com.keepit.search.index.graph.collection

import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.search.ArticleStore
import com.keepit.search.index.IndexDirectory
import com.keepit.search.sharding.Shard
import com.keepit.shoebox.ShoeboxServiceClient
import scala.concurrent.Await
import scala.concurrent.duration._
import com.keepit.model.Collection

class StandaloneCollectionIndexer(
    indexDirectory: IndexDirectory,
    airbrake: AirbrakeNotifier,
    shoeboxClient: ShoeboxServiceClient) extends CollectionIndexer(indexDirectory, airbrake) {

  override def update(): Int = updateLock.synchronized {
    resetSequenceNumberIfReindex()

    var total = 0
    var done = false
    while (!done) {
      val data = CollectionIndexer.fetchData(sequenceNumber, 1000, shoeboxClient)
      done = data.isEmpty
      total += update("CollectionIndex", data, Shard(0, 1))
    }
    total
  }
}
