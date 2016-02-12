package com.keepit.search.index.article

import com.keepit.common.akka.SafeFuture
import com.keepit.rover.RoverServiceClient
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
import com.keepit.common.core._

class ArticleIndexer(val indexDirectory: IndexDirectory, shard: Shard[NormalizedURI], val airbrake: AirbrakeNotifier) extends Indexer[NormalizedURI, NormalizedURI, ArticleIndexable, ArticleIndexer](indexDirectory) {
  val name = "ArticleIndexer" + shard.indexNameSuffix
  def update(): Int = throw new UnsupportedOperationException()

  override val commitBatchSize = 200

  override protected def shouldDelete(indexable: ArticleIndexable): Boolean = indexable.isDeleted || !shard.contains(indexable.uri.id.get)

  private[article] def processIndexables(indexables: Seq[ArticleIndexable]): Int = updateLock.synchronized {
    doUpdate(indexables.iterator)
  }
}

class ShardedArticleIndexer(
    val indexShards: Map[Shard[NormalizedURI], ArticleIndexer],
    shoebox: ShoeboxServiceClient,
    rover: RoverServiceClient,
    val airbrake: AirbrakeNotifier,
    implicit val executionContext: ExecutionContext) extends ShardedIndexer[NormalizedURI, NormalizedURI, ArticleIndexer] {

  def update(): Int = throw new UnsupportedOperationException()

  val fetchSize = 50

  def asyncUpdate(): Future[Option[Int]] = updateLock.synchronized {
    resetSequenceNumberIfReindex()
    fetchIndexables(fetchSize).flatMap {
      case None => Future.successful(None)
      case Some((indexables, maxSeq)) => processIndexables(indexables, maxSeq).imap(Some(_))
    }
  }

  private def fetchIndexables(fetchSize: Int): Future[Option[(Seq[ArticleIndexable], SequenceNumber[NormalizedURI])]] = {
    getIndexableUris(fetchSize).flatMap {
      case None => Future.successful(None)
      case Some((uris, maxSeq)) => rover.getBestArticlesByUris(uris.map(_.id.get).toSet).map { articlesByUriId =>
        val indexables = uris.map { uri => new ArticleIndexable(uri, articlesByUriId(uri.id.get)) }
        Some((indexables, maxSeq))
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
      shoebox.getIndexableUrisWithContent(sequenceNumber, fetchSize).map(_.filter(_.seq <= catchUpSeqNumber)).map {
        case Seq() => Some((Seq(), catchUpSeqNumber))
        case uris => Some((uris, uris.map(_.seq).max))
      }
    }
  }

  //todo(Léo): promote this pattern into ShardedIndexer, make asynchronous and parallelize over shards
  private def processIndexables(indexables: Seq[ArticleIndexable], maxSeq: SequenceNumber[NormalizedURI]): Future[Int] = updateLock.synchronized {
    val futureCounts: Seq[Future[Int]] = indexShards.values.toSeq.map { indexer => SafeFuture { indexer.processIndexables(indexables) } }
    Future.sequence(futureCounts).map { counts =>
      sequenceNumber = maxSeq
      counts.sum
    }
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
}
