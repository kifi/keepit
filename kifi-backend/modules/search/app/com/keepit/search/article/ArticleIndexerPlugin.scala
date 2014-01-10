package com.keepit.search.article

import akka.actor._
import akka.pattern.ask
import akka.util.Timeout
import com.google.inject.Inject
import com.keepit.common.akka.{FortyTwoActor, UnsupportedActorMessage}
import com.keepit.common.actor.ActorInstance
import com.keepit.common.db.SequenceNumber
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.plugin.{SchedulingProperties, SchedulerPlugin}
import scala.concurrent.Await
import scala.concurrent.duration._
import com.keepit.common.service.ServiceStatus
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.search.sharding.ShardedArticleIndexer

case object Update
case object BackUp

private[article] class ArticleIndexerActor @Inject() (
    airbrake: AirbrakeNotifier,
    shardedArticleIndexer: ShardedArticleIndexer)
  extends FortyTwoActor(airbrake) with Logging {

  def receive() = {
    case Update => try {
        sender ! shardedArticleIndexer.update()
      } catch {
        case e: Exception =>
          airbrake.notify("Error indexing articles", e)
          sender ! -1
      }
    case BackUp => shardedArticleIndexer.backup()
    case m => throw new UnsupportedActorMessage(m)
  }
}

trait ArticleIndexerPlugin {
  def update(): Int
  def reindex()
  def refreshSearcher()
  def numDocs(): Int
  def sequenceNumber: SequenceNumber
  def commitSequenceNumber: SequenceNumber
  def committedAt: Option[String]
  def getIndexerFor(id: Long): ArticleIndexer
}

class ArticleIndexerPluginImpl @Inject() (
    actor: ActorInstance[ArticleIndexerActor],
    shardedArticleIndexer: ShardedArticleIndexer,
    serviceDiscovery: ServiceDiscovery,
    val scheduling: SchedulingProperties)
  extends ArticleIndexerPlugin with SchedulerPlugin with Logging {

  implicit val actorTimeout = Timeout(5 seconds)

  // plugin lifecycle methods
  override def enabled: Boolean = true
  override def onStart() {
    log.info("starting ArticleIndexerPluginImpl")
    scheduleTaskOnAllMachines(actor.system, 30 seconds, 1 minutes, actor.ref, Update)
    serviceDiscovery.thisInstance.filter(_.remoteService.healthyStatus == ServiceStatus.BACKING_UP).foreach { _ =>
      scheduleTaskOnAllMachines(actor.system, 30 minutes, 2 hours, actor.ref, BackUp)
    }
  }
  override def onStop() {
    log.info("stopping ArticleIndexerPluginImpl")
    shardedArticleIndexer.close()
  }

  override def update(): Int = {
    val future = actor.ref.ask(Update)(1 minutes).mapTo[Int]
    Await.result(future, 1 minutes)
  }

  override def reindex(): Unit = {
    shardedArticleIndexer.reindex()
    actor.ref ! Update
  }

  override def refreshSearcher(): Unit = {
    shardedArticleIndexer.refreshSearcher()
  }

  override def numDocs: Int = shardedArticleIndexer.numDocs

  override def sequenceNumber: SequenceNumber = shardedArticleIndexer.sequenceNumber

  override def commitSequenceNumber: SequenceNumber = shardedArticleIndexer.commitSequenceNumber

  override def committedAt: Option[String] = shardedArticleIndexer.committedAt

  def getIndexerFor(id: Long): ArticleIndexer = shardedArticleIndexer.getIndexerFor(id).asInstanceOf[ArticleIndexer]
}
