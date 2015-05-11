package com.keepit.search.index.article

import com.keepit.rover.RoverServiceClient
import com.keepit.search.index.IndexInfo
import com.keepit.search.index._
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.model.{ IndexableUri, NormalizedURI }
import scala.concurrent.{ Await, ExecutionContext, Future }
import com.keepit.common.db.SequenceNumber
import com.keepit.search.index.sharding.{ ShardedIndexer, Shard }
import com.google.inject.Inject
import com.keepit.common.logging.Logging
import com.keepit.common.actor.ActorInstance
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.common.plugin.SchedulingProperties
import scala.concurrent.duration._

class ArticleIndexer(val indexDirectory: IndexDirectory, shard: Shard[NormalizedURI], val airbrake: AirbrakeNotifier) extends Indexer[NormalizedURI, NormalizedURI, ArticleIndexer](indexDirectory) {
  val name = "ArticleIndexer" + shard.indexNameSuffix
  def update(): Int = throw new UnsupportedOperationException()

  override val commitBatchSize = 200

  private[article] def processIndexables(indexables: Seq[ArticleIndexable]): Int = updateLock.synchronized {
    indexables.foreach(validate)
    doUpdate(name)(indexables.iterator)
  }

  private def validate(indexable: ArticleIndexable): Unit = {
    val isValidIndexable = shard.contains(indexable.uri.id.get) || indexable.isDeleted
    if (!isValidIndexable) { throw new IllegalArgumentException(s"$indexable does not belong to $shard") }
  }

  override def indexInfos(name: String): Seq[IndexInfo] = {
    super.indexInfos(this.name)
  }

}

class ShardedArticleIndexer(
    val indexShards: Map[Shard[NormalizedURI], ArticleIndexer],
    shoebox: ShoeboxServiceClient,
    rover: RoverServiceClient,
    val airbrake: AirbrakeNotifier,
    implicit val executionContext: ExecutionContext) extends ShardedIndexer[NormalizedURI, NormalizedURI, ArticleIndexer] {

  def update(): Int = throw new UnsupportedOperationException()

  val fetchSize = 200

  def asyncUpdate(): Future[Option[Int]] = updateLock.synchronized {
    resetSequenceNumberIfReindex()
    fetchIndexables(fetchSize).map {
      _.map {
        case (shardedIndexables, maxSeq) =>
          processShardedIndexables(shardedIndexables, maxSeq)
      }
    }
  }

  private def fetchIndexables(fetchSize: Int): Future[Option[(Map[Shard[NormalizedURI], Seq[ArticleIndexable]], SequenceNumber[NormalizedURI])]] = {
    getIndexableUris(fetchSize).flatMap {
      case None => Future.successful(None)
      case Some((uris, maxSeq)) => rover.getBestArticlesByUris(uris.map(_.id.get).toSet).map { articlesByUriId =>
        val shardedIndexables = indexShards.keys.map { shard =>
          val indexables = uris.map { uri => new ArticleIndexable(uri, articlesByUriId(uri.id.get), shard) }
          shard -> indexables
        }.toMap
        Some((shardedIndexables, maxSeq))
      }
    }
  }

  private def getIndexableUris(fetchSize: Int): Future[Option[(Seq[IndexableUri], SequenceNumber[NormalizedURI])]] = {
    if (sequenceNumber >= catchUpSeqNumber) {
      shoebox.getIndexableUris(sequenceNumber, fetchSize).map {
        case Seq() => None
        case uris => Some((uris, uris.map(_.seq).max))
      }
    } else {
      log.info(s"ShardedArticleIndexer in catch up mode: skip uris with no content until seq number passes ${catchUpSeqNumber.value}")
      shoebox.getScrapedUris(sequenceNumber, fetchSize).map(_.filter(_.seq <= catchUpSeqNumber)).map {
        case Seq() => Some((Seq(), catchUpSeqNumber))
        case uris => Some((uris, uris.map(_.seq).max))
      }
    }
  }

  //todo(Léo): promote this pattern into ShardedIndexer, make asynchronous and parallelize over shards
  private def processShardedIndexables(shardedIndexables: Map[Shard[NormalizedURI], Seq[ArticleIndexable]], maxSeq: SequenceNumber[NormalizedURI]): Int = updateLock.synchronized {
    val count = indexShards.map {
      case (shard, indexer) =>
        shardedIndexables.get(shard).map(indexer.processIndexables).getOrElse(0)
    }.sum
    sequenceNumber = maxSeq
    count
  }

  override def getDbHighestSeqNum(): SequenceNumber[NormalizedURI] = {
    Await.result(shoebox.getHighestUriSeq(), 5 seconds) // todo(Léo): get rid of this Await, even though it's only called when the Indexer is instantiated
  }
}

class ArticleIndexerActor @Inject() (
    airbrake: AirbrakeNotifier,
    indexer: ShardedArticleIndexer) extends CoordinatingIndexerActor(airbrake, indexer) with Logging {
  protected def update(): Future[Boolean] = indexer.asyncUpdate().map(_.isEmpty)
}

trait ArticleIndexerPlugin extends IndexerPlugin[NormalizedURI, ArticleIndexer]

class ArticleIndexerPluginImpl @Inject() (
    actor: ActorInstance[ArticleIndexerActor],
    indexer: ShardedArticleIndexer,
    airbrake: AirbrakeNotifier,
    serviceDiscovery: ServiceDiscovery,
    val scheduling: SchedulingProperties) extends IndexerPluginImpl(indexer, actor, serviceDiscovery) with ArticleIndexerPlugin {
  override def onStart() = {
    if (serviceDiscovery.hasBackupCapability) {
      log.info(s"Build the new article index on backup machine. [Version: ${IndexerVersionProviders.Article.getVersionByStatus(serviceDiscovery)}]")
      super.onStart()
    }
  }
  override def enabled = serviceDiscovery.hasBackupCapability
}
