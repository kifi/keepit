package com.keepit.eliza


import com.keepit.common.db.slick.Database
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.common.db.Id
import com.keepit.model.NormalizedURI
import com.keepit.common.zookeeper.CentralConfig
import com.keepit.common.logging.Logging 
import com.keepit.integrity.ChangedUriSeqNumKey
import com.keepit.common.zookeeper.ServiceDiscovery

import scala.concurrent.ExecutionContext.Implicits.global

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
    serviceDiscovery: ServiceDiscovery
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
          db.readWrite{ implicit session => renormRepo.addNew(remoteSequenceNumber, updates.keys.toSeq.length, updates.keys.toSeq) }
        }
      }
      case _ => 
    }
  }

  private def updateTables(updates: Map[Id[NormalizedURI], NormalizedURI]) : Unit = {
    db.readWrite{ implicit session =>
      threadRepo.updateNormalizedUris(updates)
      userThreadRepo.updateUriIds(updates.mapValues(_.id.get))
      messageRepo.updateUriIds(updates.mapValues(_.id.get))
    }
  }



}
