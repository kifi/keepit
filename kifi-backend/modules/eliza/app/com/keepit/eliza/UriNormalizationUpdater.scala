package com.keepit.eliza


import com.keepit.common.db.slick.Database
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.common.db.{SequenceNumber, Id}
import com.keepit.model.{ChangedURI, NormalizedURI}
import com.keepit.common.zookeeper.CentralConfig
import com.keepit.common.logging.Logging
import com.keepit.integrity.URIMigrationSeqNumKey
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.eliza.model._

import akka.actor.ActorSystem

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.concurrent.duration._

import play.api.libs.json.{Json, JsObject}

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



  centralConfig.onChange(URIMigrationSeqNumKey)(checkAndUpdate)

  def localSequenceNumber(): SequenceNumber[ChangedURI] = db.readOnly{ implicit session => renormRepo.getCurrentSequenceNumber() }

  def checkAndUpdate(remoteSequenceNumberOpt: Option[SequenceNumber[ChangedURI]]) : Unit = synchronized {
    var localSeqNum = localSequenceNumber()
    log.info(s"ZKX Renormalization: Checking if I need to update. Leader: ${serviceDiscovery.isLeader()}. seqNum: ${remoteSequenceNumberOpt}. locSeqNum: ${localSeqNum}")
    remoteSequenceNumberOpt match {
      case Some(remoteSequenceNumber) if (remoteSequenceNumber>localSeqNum && serviceDiscovery.isLeader()) => {
        val upperBound = if (remoteSequenceNumber - localSequenceNumber > 500) localSequenceNumber + 500 else remoteSequenceNumber
        val thereIsMore = upperBound < remoteSequenceNumber
        val updatesFuture = shoebox.getNormalizedUriUpdates(localSequenceNumber, upperBound)
        updatesFuture.map{ updates =>
          applyUpdates(updates, reapply=true)
          db.readWrite{ implicit session => renormRepo.addNew(upperBound, updates.size, updates.map{_._1}) }
        }.onComplete{ _ =>
          log.info(s"ZKX Renormalization: Finished one batch. There is more: $thereIsMore. Remote seq num: ${remoteSequenceNumberOpt}")
          if (thereIsMore) checkAndUpdate(remoteSequenceNumberOpt)
        }
      }
      case _ =>
    }

  }

  private def applyUpdates(updates: Seq[(Id[NormalizedURI], NormalizedURI)], reapply: Boolean = false) : Unit = {
    val userThreadUpdates = db.readOnly { implicit session => updates.map{ //Note: This will need to change when we have detached threads!
        case (oldId, newNUri) => (userThreadRepo.getByUriId(oldId), newNUri.url)
      }
    }
    updateTables(updates)
    userThreadUpdates.map{
      case (userThreads, newNUrl) => userThreads.map(fixLastNotificationJson(_, newNUrl))
    }
    if (reapply){
      system.scheduler.scheduleOnce(1 minutes){
        applyUpdates(updates)
      }
    }
  }

  def fixLastNotificationJson(userThread: UserThread, newNUrl: String) : Unit = {
    val newJson = userThread.lastNotification match {
      case obj: JsObject => obj.deepMerge(Json.obj("url"->newNUrl))
      case x => x
    }
    userThread.lastMsgFromOther.foreach { msgId =>
      db.readWrite{ implicit session =>
        userThreadRepo.updateLastNotificationForMessage(userThread.user, userThread.threadId, msgId, newJson)
      }
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
