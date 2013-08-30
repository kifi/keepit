package com.keepit.eliza


import com.keepit.common.db.slick.Database
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.common.db.Id
import com.keepit.model.NormalizedURI
import com.keepit.common.zookeeper.CentralConfig
import com.keepit.common.logging.Logging 
import com.keepit.integrity.ChangedUriSeqNumKey
import com.keepit.common.zookeeper.ServiceDiscovery

import akka.actor.ActorSystem

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

import com.google.inject.{Inject, Singleton}



@Singleton
class UriNormalizationUpdater @Inject() (
    threadRepo: MessageThreadRepo, 
    userThreadRepo: UserThreadRepo, 
    messageRepo: MessageRepo, 
    shoebox: ShoeboxServiceClient, 
    db: Database,
    centralConfig: CentralConfig,
    renormRepo: UriRenormalizationTrackingRepo,
    serviceDiscovery: ServiceDiscovery,
    system: ActorSystem
  ) extends Logging {

  centralConfig.onChange(ChangedUriSeqNumKey())(checkAndUpdate _)

  def localSequenceNumber: Long = db.readOnly{ implicit session => renormRepo.getCurrentSequenceNumber() }

  def checkAndUpdate(remoteSequenceNumberOpt: Option[Long]) = synchronized {
    log.info("Renormalization: Checking if I need to update")
    remoteSequenceNumberOpt match {
      case Some(remoteSequenceNumber) if (remoteSequenceNumber>localSequenceNumber && serviceDiscovery.isLeader) => {
        val updatesFuture = shoebox.getNormalizedUriUpdates(localSequenceNumber, remoteSequenceNumber)
        updatesFuture.map{ updates =>
          updateTables(updates)
          system.scheduler.scheduleOnce(1 minutes){
            updateTables(updates)
          } 
          db.readWrite{ implicit session => renormRepo.addNew(remoteSequenceNumber, updates.size, updates.map{_._1}) }
        }
      }
      case _ => 
    }
  }

  private def updateTables(updates: Seq[(Id[NormalizedURI], NormalizedURI)]) : Unit = {
    db.readWrite{ implicit session =>
      threadRepo.updateNormalizedUris(updates)
      userThreadRepo.updateUriIds(updates.map{ case (id, uri) => (id, uri.id.get)})
      messageRepo.updateUriIds(updates.map{case (id, uri) => (id, uri.id.get)})
    }
  }



}
