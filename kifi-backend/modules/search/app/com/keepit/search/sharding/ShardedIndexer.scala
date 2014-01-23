package com.keepit.search.sharding

import com.keepit.common.db.Id
import com.keepit.common.db.SequenceNumber
import com.keepit.search.index.IndexManager
import com.keepit.search.index.Indexer
import com.keepit.search.IndexInfo

trait ShardedIndexer[K, T <: Indexer[_]] extends IndexManager[T] {
  val indexShards: Map[Shard[K], T]
  protected val updateLock = new AnyRef
  @volatile protected var closing = false

  def commitSequenceNumber: SequenceNumber = SequenceNumber(indexShards.valuesIterator.map(indexer => indexer.commitSequenceNumber.value).min)
  def localIndexerInfo: Seq[(Int, Long)] = indexShards.valuesIterator.map{indexer => (indexer.numDocs, indexer.catchUpSeqNumber.value)}.toSeq

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

  private[this] var _catchUpSeqNumber: SequenceNumber = SequenceNumber(indexShards.valuesIterator.map(indexer => indexer.catchUpSeqNumber.value).min)
  def catchUpSeqNumber = _catchUpSeqNumber
  protected def catchUpSeqNumber_=(n: SequenceNumber) { _catchUpSeqNumber = n }

  private[this] var resetSequenceNumber = false
  protected def resetSequenceNumberIfReindex() {
    if (resetSequenceNumber) {
      resetSequenceNumber = false
      _sequenceNumber = SequenceNumber.MinValue
    }
  }

  def getIndexerFor(id: Id[K]): T = getIndexer(indexShards.keysIterator.find(_.contains(id)).get)
  def getIndexer(shard: Shard[K]): T = indexShards(shard)

  def indexInfos(name: String): Seq[IndexInfo] = {
    indexShards.flatMap{ case (shard, indexer) => indexer.indexInfos(shard.indexNameSuffix) }.toSeq
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

  def backup(): Unit = updateLock.synchronized {
    indexShards.valuesIterator.foreach(_.backup())
  }

  def close(): Unit = {
    closing = true
    indexShards.valuesIterator.foreach(_.close())
  }
}
