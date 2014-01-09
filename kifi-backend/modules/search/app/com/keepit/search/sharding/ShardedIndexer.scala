package com.keepit.search.sharding

import com.keepit.common.db.SequenceNumber
import com.keepit.search.index.Indexer

trait ShardedIndexer[T <: Indexer[_]] {
  val indexShards: Map[Shard, T]
  protected val updateLock = new AnyRef

  private[this] var _sequenceNumber: SequenceNumber = {
    SequenceNumber(indexShards.valuesIterator.map(indexer => indexer.commitSequenceNumber.map(_.value).getOrElse(-1L)).min)
  }

  def sequenceNumber = _sequenceNumber

  private[search] def sequenceNumber_=(n: SequenceNumber) {
    _sequenceNumber = n
  }

  private[this] var resetSequenceNumber = false
  protected def resetSequenceNumberIfReindex() {
    if (resetSequenceNumber) {
      resetSequenceNumber = false
      sequenceNumber = SequenceNumber.MinValue
    }
  }

  def getIndexer(shard: Shard): T = indexShards(shard)

  def update(): Int

  def reindex(): Unit = {
    indexShards.valuesIterator.foreach(_.reindex())
    resetSequenceNumber = true
  }

  def backup(): Unit = indexShards.valuesIterator.foreach(_.backup())

  def close(): Unit = indexShards.valuesIterator.foreach(_.close())
}
