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
    if (localIndexerInfo.map{ case (numDocs, _) => numDocs}.max == 0) setupCatchUpSeqNum()    // this only happens when we build index from scratch

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
      if (sequenceNumber == catchUpSeqNumber) done = true
    }
    total
  }

  def update(fsize: Int): Int = updateLock.synchronized { // for testing
    resetSequenceNumberIfReindex()
    if (localIndexerInfo.map{ case (numDocs, _) => numDocs}.max == 0) setupCatchUpSeqNum()

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

  private def setupCatchUpSeqNum(){
    val dbSeq = SequenceNumber(Await.result(shoeboxClient.getHighestUriSeq(), 5 seconds))
    // if subindexer is empty, dbSeq is safe for it; otherwise, use its own catchUpSeqNum. Take min will be safe for everyone.
    val seqNum = SequenceNumber(localIndexerInfo.map{case (numDocs, catchUpSeqNum) => if (numDocs == 0) dbSeq.value else catchUpSeqNum}.min)
    log.info(s"setting up global catchup Seq Num: ${seqNum.value}")
    indexShards.valuesIterator.foreach{_.catchUpSeqNumber_=(seqNum) }
    catchUpSeqNumber_=(seqNum)
  }

  override def reindex(): Unit = {
    setupCatchUpSeqNum()
    super.reindex()
  }
}
