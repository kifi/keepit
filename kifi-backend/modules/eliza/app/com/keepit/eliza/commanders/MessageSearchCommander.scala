package com.keepit.eliza.commanders

import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.slick.Database
import com.keepit.search.SearchServiceClient
import com.keepit.eliza.model.{ MessageThreadRepo, UserThreadRepo, MessageThread, MessageSearchHistoryRepo }
import com.keepit.common.logging.Logging
import com.keepit.common.db.Id
import com.keepit.model.{ Keep, User }
import com.keepit.common.db.ExternalId
import com.keepit.common.akka.SafeFuture

import play.api.libs.concurrent.Execution.Implicits.defaultContext

import com.google.inject.Inject

import scala.concurrent.Future

class MessageSearchCommander @Inject() (
    threadRepo: MessageThreadRepo,
    userThreadRepo: UserThreadRepo,
    db: Database,
    search: SearchServiceClient,
    notificationJsonMaker: NotificationJsonMaker,
    notificationDeliveryCommander: NotificationDeliveryCommander,
    historyRepo: MessageSearchHistoryRepo,
    implicit val publicIdConfig: PublicIdConfiguration) extends Logging {

  def searchMessages(userId: Id[User], query: String, page: Int, storeInHistory: Boolean): Future[Seq[NotificationJson]] = {
    val resultExtIdsFut = search.searchMessages(userId, query, page)
    //async update search history for the user
    if (storeInHistory) {
      SafeFuture("message search history update") {
        db.readWrite { implicit session =>
          val history = historyRepo.getOrCreate(userId)
          if (!history.optOut) {
            historyRepo.save(history.withNewQuery(query))
          }
        }
      }
    }
    resultExtIdsFut.flatMap { protoExtIds =>
      val keepIds = protoExtIds.flatMap(Keep.decodePublicId(_).toOption).toSet[Id[Keep]]
      db.readOnlyReplica { implicit session =>
        val threadIds = threadRepo.getByKeepIds(keepIds).values.map(_.id.get).toSet
        notificationDeliveryCommander.getNotificationsByUser(userId, UserThreadQuery(threadIds = Some(threadIds), limit = threadIds.size), includeUriSummary = false)
      }
    }
  }

  def getHistory(userId: Id[User]): (Seq[String], Seq[String], Boolean) = {
    val history = db.readWrite { implicit session =>
      historyRepo.getOrCreate(userId)
    }
    (history.queries, history.emails, history.optOut)
  }

  def setHistoryOptOut(userId: Id[User], optOut: Boolean): Boolean = {
    db.readWrite { implicit session =>
      historyRepo.save(
        historyRepo.getOrCreate(userId).withOptOut(optOut)
      )
    }
    optOut
  }

  def getHistoryOptOut(userId: Id[User]): Boolean = {
    db.readWrite { implicit session =>
      historyRepo.getOrCreate(userId).optOut
    }
  }

  def clearHistory(userId: Id[User]): Unit = {
    db.readWrite { implicit session =>
      historyRepo.save(
        historyRepo.getOrCreate(userId).withoutHistory()
      )
    }
  }

}
