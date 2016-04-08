package com.keepit.search.index.graph.keep

import com.keepit.common.akka.SafeFuture
import com.keepit.search.index._
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.model.{ CrossServiceKeepAndTags, NormalizedURI, Keep }
import scala.concurrent.Future
import com.keepit.common.db.SequenceNumber
import com.keepit.search.index.sharding.{ ShardedIndexer, Shard }
import com.google.inject.Inject
import com.keepit.common.logging.Logging
import com.keepit.common.actor.ActorInstance
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.common.plugin.SchedulingProperties
import com.keepit.common.core._

class KeepIndexer(indexDirectory: IndexDirectory, shard: Shard[NormalizedURI], val airbrake: AirbrakeNotifier) extends Indexer[Keep, Keep, KeepIndexable, KeepIndexer](indexDirectory, KeepFields.maxPrefixLength, 4) {
  val name = "KeepIndexer" + shard.indexNameSuffix
  def update(): Int = throw new UnsupportedOperationException()

  override val commitBatchSize = 200

  override protected def shouldDelete(indexable: KeepIndexable): Boolean = indexable.isDeleted || !shard.contains(indexable.keep.uriId)

  private[keep] def processIndexables(indexables: Seq[KeepIndexable]): Int = updateLock.synchronized {
    doUpdate(indexables.iterator)
  }
}

class ShardedKeepIndexer(
    val indexShards: Map[Shard[NormalizedURI], KeepIndexer],
    shoebox: ShoeboxServiceClient,
    val airbrake: AirbrakeNotifier) extends ShardedIndexer[NormalizedURI, Keep, KeepIndexer] {

  import play.api.libs.concurrent.Execution.Implicits.defaultContext

  def update(): Int = throw new UnsupportedOperationException()

  val fetchSize = ShardedIndexer.computeFetchSize(indexShards.keySet, localFetchSize = 100, maxFetchSize = 500)

  def asyncUpdate(): Future[Boolean] = updateLock.synchronized {
    resetSequenceNumberIfReindex()
    fetchIndexables(sequenceNumber, fetchSize).flatMap {
      case None => Future.successful(true)
      case Some((indexables, maxSeq, exhausted)) => processIndexables(indexables, maxSeq).imap(_ => exhausted)
    }
  }

  private def fetchIndexables(seq: SequenceNumber[Keep], fetchSize: Int): Future[Option[(Seq[KeepIndexable], SequenceNumber[Keep], Boolean)]] = {
    shoebox.getCrossServiceKeepsAndTagsChanged(seq, fetchSize).map { changedKeepsAndTags =>
      if (changedKeepsAndTags.nonEmpty) {
        val indexables = changedKeepsAndTags.map { case CrossServiceKeepAndTags(keep, source, tags) => new KeepIndexable(keep, source, tags) }
        val exhausted = changedKeepsAndTags.isEmpty
        val maxSeq = changedKeepsAndTags.map(_.keep.seq).max
        Some((indexables, maxSeq, exhausted))
      } else {
        None
      }
    }
  }

  private def processIndexables(indexables: Seq[KeepIndexable], maxSeq: SequenceNumber[Keep]): Future[Int] = updateLock.synchronized {
    val futureCounts: Seq[Future[Int]] = indexShards.values.toSeq.map { indexer => SafeFuture { indexer.processIndexables(indexables) } }
    Future.sequence(futureCounts).map { counts =>
      sequenceNumber = maxSeq
      counts.sum
    }
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
