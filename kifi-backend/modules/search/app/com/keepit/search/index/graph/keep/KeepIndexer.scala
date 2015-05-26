package com.keepit.search.index.graph.keep

import com.keepit.search.index.IndexInfo
import com.keepit.search.index._
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.model.{ KeepAndTags, NormalizedURI, Keep }
import scala.concurrent.Future
import com.keepit.common.db.SequenceNumber
import com.keepit.search.index.sharding.{ ShardedIndexer, Shard }
import com.google.inject.Inject
import com.keepit.common.logging.Logging
import com.keepit.common.actor.ActorInstance
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.common.plugin.SchedulingProperties

class KeepIndexer(indexDirectory: IndexDirectory, shard: Shard[NormalizedURI], val airbrake: AirbrakeNotifier) extends Indexer[Keep, Keep, KeepIndexer](indexDirectory) {
  val name = "KeepIndexer" + shard.indexNameSuffix
  def update(): Int = throw new UnsupportedOperationException()

  override val commitBatchSize = 200

  private[keep] def processIndexables(indexables: Seq[KeepIndexable]): Int = updateLock.synchronized {
    indexables.foreach(validate)
    doUpdate(name)(indexables.iterator)
  }

  private def validate(indexable: KeepIndexable): Unit = {
    val isValidIndexable = shard.contains(indexable.keep.uriId) || indexable.isDeleted
    if (!isValidIndexable) { throw new IllegalArgumentException(s"$indexable does not belong to $shard") }
  }

  override def indexInfos(name: String): Seq[IndexInfo] = {
    super.indexInfos(this.name)
  }

}

class ShardedKeepIndexer(
    val indexShards: Map[Shard[NormalizedURI], KeepIndexer],
    shoebox: ShoeboxServiceClient,
    val airbrake: AirbrakeNotifier) extends ShardedIndexer[NormalizedURI, Keep, KeepIndexer] {

  import play.api.libs.concurrent.Execution.Implicits.defaultContext

  def update(): Int = throw new UnsupportedOperationException()

  val fetchSize = 200

  def asyncUpdate(): Future[Boolean] = updateLock.synchronized {
    resetSequenceNumberIfReindex()
    fetchIndexables(sequenceNumber, fetchSize).map {
      case Some((shardedIndexables, maxSeq, exhausted)) =>
        processShardedIndexables(shardedIndexables, maxSeq)
        exhausted
      case None =>
        true
    }
  }

  private def fetchIndexables(seq: SequenceNumber[Keep], fetchSize: Int): Future[Option[(Map[Shard[NormalizedURI], Seq[KeepIndexable]], SequenceNumber[Keep], Boolean)]] = {
    shoebox.getKeepsAndTagsChanged(seq, fetchSize).map { changedKeepsAndTags =>
      if (changedKeepsAndTags.nonEmpty) {
        val shardedIndexables = indexShards.keys.map { shard =>
          val indexables = changedKeepsAndTags.map { case KeepAndTags(keep, tags) => new KeepIndexable(keep, tags, shard) }
          shard -> indexables
        }.toMap
        val exhausted = changedKeepsAndTags.length < fetchSize
        val maxSeq = changedKeepsAndTags.map(_.keep.seq).max
        Some((shardedIndexables, maxSeq, exhausted))
      } else {
        None
      }
    }
  }

  //todo(Léo): promote this pattern into ShardedIndexer, make asynchronous and parallelize over shards
  private def processShardedIndexables(shardedIndexables: Map[Shard[NormalizedURI], Seq[KeepIndexable]], maxSeq: SequenceNumber[Keep]): Int = updateLock.synchronized {
    val count = indexShards.map {
      case (shard, indexer) =>
        shardedIndexables.get(shard).map(indexer.processIndexables).getOrElse(0)
    }.sum
    sequenceNumber = maxSeq
    count
  }
}

class KeepIndexerActor @Inject() (
    airbrake: AirbrakeNotifier,
    indexer: ShardedKeepIndexer) extends CoordinatingIndexerActor(airbrake, indexer) with Logging {
  protected def update(): Future[Boolean] = indexer.asyncUpdate()
}

trait KeepIndexerPlugin extends IndexerPlugin[Keep, KeepIndexer]

class KeepIndexerPluginImpl @Inject() (
  actor: ActorInstance[KeepIndexerActor],
  indexer: ShardedKeepIndexer,
  airbrake: AirbrakeNotifier,
  serviceDiscovery: ServiceDiscovery,
  val scheduling: SchedulingProperties) extends IndexerPluginImpl(indexer, actor, serviceDiscovery) with KeepIndexerPlugin
