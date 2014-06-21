package com.keepit.cortex.dbmodel

import com.google.inject.{Inject, Singleton}
import com.keepit.common.db.slick.Database
import com.keepit.shoebox.ShoeboxServiceClient
import scala.concurrent.Future
import com.keepit.common.db.SequenceNumber
import com.keepit.model.NormalizedURI
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.model.Keep
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.akka.FortyTwoActor
import scala.concurrent.duration._
import scala.util.Random
import scala.concurrent.Await
import com.keepit.common.actor.ActorInstance
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.common.plugin.SchedulerPlugin
import com.keepit.common.plugin.SchedulingProperties

object CortexDataIngestion{
  trait CortexDataIngestionMessage
  case object UpdateURI extends CortexDataIngestionMessage
  case object UpdateKeep extends CortexDataIngestionMessage
}

trait CortexDataIngestionPlugin

@Singleton
private[cortex] class CortexDataIngestionPluginImpl @Inject()(
  actor: ActorInstance[CortexDataIngestionActor],
  serviceDiscovery: ServiceDiscovery,
  val scheduling: SchedulingProperties
) extends CortexDataIngestionPlugin with SchedulerPlugin{
  import CortexDataIngestion._

  override def enabled: Boolean = true

  val name: String = getClass.toString

  override def onStart() {
    log.info(s"starting $name")
    scheduleTaskOnLeader(actor.system, 1 minute, 1 minute, actor.ref, UpdateURI)
    scheduleTaskOnLeader(actor.system, 1 minute, 1 minute, actor.ref, UpdateKeep)
  }

  override def onStop() {
    log.info(s"stopping $name")
  }
}

class CortexDataIngestionActor @Inject()(
  updater: CortexDataIngestionUpdater,
  airbrake: AirbrakeNotifier
) extends FortyTwoActor(airbrake){
  import CortexDataIngestion._

  private val fetchSize = 500
  private val rng = new Random()
  private def randomDelay = (5 + rng.nextInt(10)) seconds

  def receive = {
    case UpdateURI =>
      println("\n===\n\none round of uri ingestion")
      val sz = Await.result(updater.updateURIRepo(fetchSize), 5 seconds)
      if (sz == fetchSize) context.system.scheduler.scheduleOnce(randomDelay, self, UpdateURI)

    case UpdateKeep =>
      println("\n===\n\none round of keep ingestion")
      val sz = Await.result(updater.updateKeepRepo(fetchSize), 5 seconds)
      if (sz == fetchSize) context.system.scheduler.scheduleOnce(randomDelay, self, UpdateKeep)
  }
}


private[cortex] class CortexDataIngestionUpdater @Inject()(
  db: Database,
  shoebox: ShoeboxServiceClient,
  uriRepo: CortexURIRepo,
  keepRepo: CortexKeepRepo
) {
  private implicit def toURISeq(seq: SequenceNumber[CortexURI]) = SequenceNumber[NormalizedURI](seq.value)
  private implicit def toKeepSeq(seq: SequenceNumber[CortexKeep]) = SequenceNumber[Keep](seq.value)

  def updateURIRepo(fetchSize: Int): Future[Int] = {
    val seq = db.readOnly{ implicit s => uriRepo.getMaxSeq}

    shoebox.getCortexURIs(seq, fetchSize).map{ uris =>
      db.readWrite{ implicit s =>
        uris.foreach{ uri =>
          uriRepo.getByURIId(uri.uriId) match {
            case None => uriRepo.save(uri)
            case Some(cortexUri) => uriRepo.save(uri.copy(id = cortexUri.id))
          }
        }
      }
      uris.size
    }
  }

  def updateKeepRepo(fetchSize: Int): Future[Int] = {
    val seq = db.readOnly{ implicit s => keepRepo.getMaxSeq}

    shoebox.getCortexKeeps(seq, fetchSize).map{ keeps =>
      db.readWrite { implicit s =>
        keeps.foreach{ keep =>
          keepRepo.getByKeepId(keep.keepId) match {
            case None => keepRepo.save(keep)
            case Some(cortexKeep) => keepRepo.save(keep.copy(id = cortexKeep.id))
          }
        }
      }
      keeps.size
    }
  }
}
