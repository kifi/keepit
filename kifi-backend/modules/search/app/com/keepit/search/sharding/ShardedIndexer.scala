package com.keepit.search.sharding

import com.keepit.common.db.SequenceNumber
import com.keepit.search.index.Indexer

trait ShardedIndexer[T <: Indexer[_]] {
  val indexShards: Map[Shard, T]
  protected val updateLock = new AnyRef

  def commitSequenceNumber: SequenceNumber = SequenceNumber(indexShards.valuesIterator.map(indexer => indexer.commitSequenceNumber.value).min)

  def committedAt: Option[String] = {
    indexShards.valuesIterator.reduce{ (a, b) =>
      if (a.commitSequenceNumber.value < b.commitSequenceNumber.value) a else b
    }.committedAt
  }

  private[this] var _sequenceNumber: SequenceNumber = commitSequenceNumber

  def sequenceNumber = _sequenceNumber

  protected def sequenceNumber_=(n: SequenceNumber) {
    _sequenceNumber = n
  }

  private[this] var resetSequenceNumber = false
  protected def resetSequenceNumberIfReindex() {
    if (resetSequenceNumber) {
      resetSequenceNumber = false
      _sequenceNumber = SequenceNumber.MinValue
    }
  }

  def getIndexerFor(id: Long): T = getIndexer(indexShards.keysIterator.find(_.contains(id)).get)
  def getIndexer(shard: Shard): T = indexShards(shard)

  def numDocs: Int = indexShards.valuesIterator.map(_.numDocs).sum

  def update(): Int

  def reindex(): Unit = {
    indexShards.valuesIterator.foreach(_.reindex())
    resetSequenceNumber = true
  }

  def refreshSearcher(): Unit = {
    indexShards.valuesIterator.foreach(_.refreshSearcher())
  }

  def backup(): Unit = indexShards.valuesIterator.foreach(_.backup())

  def close(): Unit = indexShards.valuesIterator.foreach(_.close())
}
