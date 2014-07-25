package com.keepit.search.graph.keep

import com.keepit.search.index._
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.model.{ NormalizedURI, Keep }
import scala.concurrent.Future
import com.keepit.common.db.SequenceNumber
import com.keepit.search.sharding.{ ShardedIndexer, Shard }
import com.google.inject.Inject
import com.keepit.common.logging.Logging
import com.keepit.common.actor.ActorInstance
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.common.plugin.SchedulingProperties

class KeepIndexer(indexDirectory: IndexDirectory, shard: Shard[NormalizedURI], val airbrake: AirbrakeNotifier) extends Indexer[Keep, Keep, KeepIndexer](indexDirectory, KeepFields.decoders) {
  val name = "KeepIndexer" + shard.indexNameSuffix
  def update(): Int = throw new UnsupportedOperationException()

  override val commitBatchSize = 500

  private[keep] def processIndexables(indexables: Seq[KeepIndexable]): Int = updateLock.synchronized {
    indexables.foreach(validate)
    doUpdate(name)(indexables.iterator)
  }

  private def validate(indexable: KeepIndexable): Unit = {
    if (!shard.contains(indexable.keep.uriId)) { throw new IllegalArgumentException(s"$indexable does not belong to $shard") }
  }
}

class ShardedKeepIndexer(
    val indexShards: Map[Shard[NormalizedURI], KeepIndexer],
    shoebox: ShoeboxServiceClient,
    val airbrake: AirbrakeNotifier) extends ShardedIndexer[NormalizedURI, Keep, KeepIndexer] {

  import play.api.libs.concurrent.Execution.Implicits.defaultContext

  def update(): Int = throw new UnsupportedOperationException()

  val fetchSize = 1000

  private[keep] def asyncUpdate(): Future[Boolean] = updateLock.synchronized {
    resetSequenceNumberIfReindex()
    fetchIndexables(sequenceNumber, fetchSize).map {
      case (shardedIndexables, maxSeq, exhausted) =>
        processShardedIndexables(shardedIndexables, maxSeq)
        exhausted
    }
  }

  private def fetchIndexables(seq: SequenceNumber[Keep], fetchSize: Int): Future[(Map[Shard[NormalizedURI], Seq[KeepIndexable]], SequenceNumber[Keep], Boolean)] = {
    shoebox.getBookmarksChanged(seq, fetchSize).map { changedKeeps =>
      val shardedKeeps = changedKeeps.groupBy(keep => indexShards.keysIterator.find(_.contains(keep.uriId)))
      val shardedIndexables = shardedKeeps.collect {
        case (Some(shard), keeps) =>
          val indexables = keeps.map(keep => new KeepIndexable(keep))
          (shard -> indexables)
      }
      val exhausted = changedKeeps.length < fetchSize
      val maxSeq = changedKeeps.map(_.seq).max
      (shardedIndexables, maxSeq, exhausted)
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
  indexer: KeepIndexer,
  airbrake: AirbrakeNotifier,
  serviceDiscovery: ServiceDiscovery,
  val scheduling: SchedulingProperties) extends IndexerPluginImpl(indexer, actor, serviceDiscovery) with KeepIndexerPlugin
