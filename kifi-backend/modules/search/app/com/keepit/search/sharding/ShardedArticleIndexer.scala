package com.keepit.search.sharding

import com.keepit.model.NormalizedURI
import com.keepit.search.ArticleStore
import com.keepit.search.article.ArticleIndexer
import com.keepit.shoebox.ShoeboxServiceClient
import scala.concurrent.Await
import scala.concurrent.duration._
import com.keepit.common.db.SequenceNumber
import com.keepit.model.NormalizedURIStates
import com.keepit.common.logging.Logging

class ShardedArticleIndexer(
  override val indexShards: Map[Shard[NormalizedURI], ArticleIndexer],
  articleStore: ArticleStore,
  shoeboxClient : ShoeboxServiceClient
) extends ShardedIndexer[NormalizedURI, ArticleIndexer] with Logging{

  private val fetchSize = 2000

  def update(): Int = updateLock.synchronized {
    resetSequenceNumberIfReindex()
    var total = 0
    var done = false
    while (!done && !closing) {
      val uris =  if (sequenceNumber.value >= catchUpSeqNumber.value) Await.result(shoeboxClient.getIndexableUris(sequenceNumber.value, fetchSize), 180 seconds)
      else {
        log.info(s"ShardedArticleIndexer in catch up mode: skip active uris until seq number passes ${catchUpSeqNumber.value}")
        val uris = Await.result(shoeboxClient.getScrapedUris(sequenceNumber.value, fetchSize), 180 seconds).filter(_.seq.value <= catchUpSeqNumber.value)
        if (uris.nonEmpty) uris else  { sequenceNumber = catchUpSeqNumber; return total }
      }
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

  def update(fsize: Int): Int = updateLock.synchronized { // for testing
    resetSequenceNumberIfReindex()

    var total = 0
    val uris = if (sequenceNumber.value >= catchUpSeqNumber.value) Await.result(shoeboxClient.getIndexableUris(sequenceNumber.value, fsize), 180 seconds)
      else {
        val uris = Await.result(shoeboxClient.getScrapedUris(sequenceNumber.value, fsize), 180 seconds).filter(_.seq.value <= catchUpSeqNumber.value)
        if (uris.nonEmpty) uris else { sequenceNumber = catchUpSeqNumber; return total }
      }
    indexShards.foldLeft(uris){ case (toBeIndexed, (shard, indexer)) =>
      val (next, rest) = toBeIndexed.partition{ uri => shard.contains(uri.id.get) }
      total += indexer.update(shard.indexNameSuffix, next, shard)
      rest
    }
    if (uris.nonEmpty) sequenceNumber = uris.last.seq
    total
  }

  override def reindex(): Unit = {
    super.reindex()
    val seqNum: SequenceNumber = SequenceNumber(Await.result(shoeboxClient.getHighestUriSeq(), 5 seconds))
    indexShards.valuesIterator.foreach{_.catchUpSeqNumber_=(seqNum)}
    catchUpSeqNumber_=(seqNum)
  }
}
