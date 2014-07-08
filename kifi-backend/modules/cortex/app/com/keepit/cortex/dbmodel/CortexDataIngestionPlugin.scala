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
import scala.util.Success
import scala.util.Failure

object CortexDataIngestion{
  trait CortexDataIngestionMessage
  case object UpdateURI extends CortexDataIngestionMessage
  case object UpdateKeep extends CortexDataIngestionMessage
}

trait CortexDataIngestionPlugin

@Singleton
private class CortexDataIngestionPluginImpl @Inject()(
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
  private var isUpdatingURI = false
  private var isUpdatingKeep = false

  def receive = {
    case UpdateURI =>
      if (!isUpdatingURI){
        isUpdatingURI = true
        updater.updateURIRepo(fetchSize).map{ sz =>
          log.info(s"updated $sz uris from shoebox")
          if (sz == fetchSize) context.system.scheduler.scheduleOnce(randomDelay, self, UpdateURI)
        }.onComplete{
          case Success(_) => isUpdatingURI = false
          case Failure(fail) =>  {isUpdatingURI = false; airbrake.notify(fail.getMessage)}
        }
      }

    case UpdateKeep =>
      if (!isUpdatingKeep){
        isUpdatingKeep = true
        updater.updateKeepRepo(fetchSize).map{ sz =>
          log.info(s"updated $sz keeps from shoebox")
          if (sz == fetchSize) context.system.scheduler.scheduleOnce(randomDelay, self, UpdateKeep)
        }.onComplete{
          case Success(_) => isUpdatingKeep = false
          case Failure(fail) => {isUpdatingKeep = false; airbrake.notify(fail.getMessage)}
        }
      }
  }
}


private class CortexDataIngestionUpdater @Inject()(
  db: Database,
  shoebox: ShoeboxServiceClient,
  uriRepo: CortexURIRepo,
  keepRepo: CortexKeepRepo
) {
  private implicit def toURISeq(seq: SequenceNumber[CortexURI]) = SequenceNumber[NormalizedURI](seq.value)
  private implicit def toKeepSeq(seq: SequenceNumber[CortexKeep]) = SequenceNumber[Keep](seq.value)
  private val DB_BATCH_SIZE = 50

  def updateURIRepo(fetchSize: Int): Future[Int] = {
    val seq = db.readOnlyMaster{ implicit s => uriRepo.getMaxSeq}

    shoebox.getIndexable(seq, fetchSize).map { uris =>
      uris.map { CortexURI.fromURI(_) } grouped (DB_BATCH_SIZE) foreach { uris =>
        db.readWrite { implicit s =>
          uris foreach { uri =>
            uriRepo.getByURIId(uri.uriId) match {
              case None => uriRepo.save(uri)
              case Some(cortexUri) => uriRepo.save(uri.copy(id = cortexUri.id))
            }
          }
        }
      }
      uris.size
    }
  }

  def updateKeepRepo(fetchSize: Int): Future[Int] = {

    val seq = db.readOnlyMaster{ implicit s => keepRepo.getMaxSeq}

    shoebox.getBookmarksChanged(seq, fetchSize).map { keeps =>
      keeps.map { CortexKeep.fromKeep(_) } grouped (DB_BATCH_SIZE) foreach { keeps =>
        db.readWrite { implicit s =>
          keeps foreach { keep =>
            keepRepo.getByKeepId(keep.keepId) match {
              case None => keepRepo.save(keep)
              case Some(cortexKeep) => keepRepo.save(keep.copy(id = cortexKeep.id))
            }
          }
        }
      }
      keeps.size
    }
  }
}
