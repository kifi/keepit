package com.keepit.search.index.sharding

import com.keepit.common.db.Id
import com.keepit.common.db.SequenceNumber
import com.keepit.search.index.IndexManager
import com.keepit.search.index.Indexer
import com.keepit.search.index.IndexInfo
import com.keepit.common.logging.Logging

trait ShardedIndexer[K, S, I <: Indexer[_, S, I]] extends IndexManager[S, I] with Logging {
  val indexShards: Map[Shard[K], I]
  protected val updateLock = new AnyRef
  @volatile protected var closing = false

  def commitSequenceNumber: SequenceNumber[S] = indexShards.valuesIterator.map(indexer => indexer.commitSequenceNumber).min

  def committedAt: Option[String] = {
    indexShards.valuesIterator.reduce { (a, b) =>
      if (a.commitSequenceNumber.value < b.commitSequenceNumber.value) a else b
    }.committedAt
  }

  private[this] var _sequenceNumber: SequenceNumber[S] = commitSequenceNumber

  def sequenceNumber = _sequenceNumber

  protected def sequenceNumber_=(n: SequenceNumber[S]) {
    _sequenceNumber = n
  }

  protected def getDbHighestSeqNum(): SequenceNumber[S] = SequenceNumber.ZERO

  private[this] lazy val catchUpSeqNum: SequenceNumber[S] = {
    val dbSeq = getDbHighestSeqNum()
    val safeSeq = indexShards.valuesIterator.map { indexer => if (indexer.numDocs == 0) dbSeq else indexer.catchUpSeqNumber }.min
    indexShards.valuesIterator.foreach { indexer => if (indexer.catchUpSeqNumber < safeSeq) indexer.catchUpSeqNumber_=(safeSeq) }
    safeSeq
  }
  def catchUpSeqNumber = catchUpSeqNum

  private[this] var resetSequenceNumber = false
  protected def resetSequenceNumberIfReindex() {
    if (resetSequenceNumber) {
      resetSequenceNumber = false
      _sequenceNumber = SequenceNumber.MinValue
    }
  }

  def getIndexerFor(id: Id[K]): I = getIndexer(indexShards.keysIterator.find(_.contains(id)).get)
  def getIndexer(shard: Shard[K]): I = indexShards(shard)

  def indexInfos(name: String): Seq[IndexInfo] = {
    indexShards.flatMap { case (shard, indexer) => indexer.indexInfos(shard.indexNameSuffix) }.toSeq
  }

  def numDocs: Int = indexShards.valuesIterator.map(_.numDocs).sum

  def update(): Int

  def reindex(): Unit = updateLock.synchronized {
    indexShards.valuesIterator.foreach(_.reindex())
    resetSequenceNumber = true
  }

  def refreshSearcher(): Unit = {
    indexShards.valuesIterator.foreach(_.refreshSearcher())
  }

  def refreshWriter(): Unit = {
    indexShards.valuesIterator.foreach(_.refreshWriter())
  }

  def warmUpIndexDirectory(): Unit = {
    indexShards.valuesIterator.foreach(_.warmUpIndexDirectory())
  }

  def backup(): Unit = updateLock.synchronized {
    indexShards.valuesIterator.foreach(_.backup())
  }

  override def lastBackup: Long = {
    indexShards.valuesIterator.map(_.lastBackup).max
  }

  def close(): Unit = {
    closing = true
    indexShards.valuesIterator.foreach(_.close())
  }
}
