package com.keepit.search.index.sharding

import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.model.NormalizedURI
import com.keepit.search.ArticleStore
import com.keepit.search.index.article.DeprecatedArticleIndexer
import com.keepit.shoebox.ShoeboxServiceClient
import scala.concurrent.Await
import scala.concurrent.duration._
import com.keepit.common.db.SequenceNumber
import com.keepit.common.logging.Logging

class DeprecatedShardedArticleIndexer(
    override val indexShards: Map[Shard[NormalizedURI], DeprecatedArticleIndexer],
    articleStore: ArticleStore,
    override val airbrake: AirbrakeNotifier,
    shoeboxClient: ShoeboxServiceClient) extends ShardedIndexer[NormalizedURI, NormalizedURI, DeprecatedArticleIndexer] with Logging {

  private val fetchSize = 2000

  def update(): Int = updateLock.synchronized {
    resetSequenceNumberIfReindex()

    var total = 0
    var done = false
    while (!done && !closing) {
      val uris = if (sequenceNumber >= catchUpSeqNumber) Await.result(shoeboxClient.getIndexableUris(sequenceNumber, fetchSize), 180 seconds)
      else {
        log.info(s"ShardedArticleIndexer in catch up mode: skip active uris until seq number passes ${catchUpSeqNumber.value}")
        val uris = Await.result(shoeboxClient.getIndexableUrisWithContent(sequenceNumber, fetchSize), 180 seconds).filter(_.seq <= catchUpSeqNumber)
        if (uris.nonEmpty) uris else { sequenceNumber = catchUpSeqNumber; return total }
      }
      done = uris.isEmpty

      indexShards.foldLeft(uris) {
        case (toBeIndexed, (shard, indexer)) =>
          val (next, rest) = toBeIndexed.partition { uri => shard.contains(uri.id.get) }
          total += indexer.update(shard.indexNameSuffix, next, shard)
          rest
      }
      if (!done) sequenceNumber = uris.last.seq
      if (sequenceNumber == catchUpSeqNumber) done = true
    }
    total
  }

  def update(fsize: Int): Int = updateLock.synchronized { // for testing
    resetSequenceNumberIfReindex()

    var total = 0
    val uris = if (sequenceNumber >= catchUpSeqNumber) Await.result(shoeboxClient.getIndexableUris(sequenceNumber, fsize), 180 seconds)
    else {
      val uris = Await.result(shoeboxClient.getIndexableUrisWithContent(sequenceNumber, fsize), 180 seconds).filter(_.seq <= catchUpSeqNumber)
      if (uris.nonEmpty) uris else { sequenceNumber = catchUpSeqNumber; return total }
    }
    indexShards.foldLeft(uris) {
      case (toBeIndexed, (shard, indexer)) =>
        val (next, rest) = toBeIndexed.partition { uri => shard.contains(uri.id.get) }
        total += indexer.update(shard.indexNameSuffix, next, shard)
        rest
    }
    if (uris.nonEmpty) sequenceNumber = uris.last.seq
    total
  }

  override def getDbHighestSeqNum(): SequenceNumber[NormalizedURI] = {
    Await.result(shoeboxClient.getHighestUriSeq(), 5 seconds)
  }

}
