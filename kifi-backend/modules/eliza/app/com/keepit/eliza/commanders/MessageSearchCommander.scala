package com.keepit.eliza.commanders


import com.keepit.common.db.slick.Database
import com.keepit.search.SearchServiceClient
import com.keepit.eliza.model.{MessageThreadRepo, UserThreadRepo, MessageThread}
import com.keepit.common.logging.Logging
import com.keepit.common.db.Id
import com.keepit.model.User
import com.keepit.common.db.ExternalId

import play.api.libs.concurrent.Execution.Implicits.defaultContext

import com.google.inject.Inject

import scala.concurrent.Future


class MessageSearchCommander @Inject() (
  threadRepo: MessageThreadRepo,
  userThreadRepo: UserThreadRepo,
  db: Database,
  search: SearchServiceClient,
  notificationUpdater: NotificationUpdater) extends Logging {

  def searchMessages(userId: Id[User], query: String, page: Int): Future[Notifications] = {
    search.searchMessages(userId, query, page).flatMap{ protoExtIds =>
      val notifs = db.readOnly { implicit session =>
        val threads = protoExtIds.map{ s => threadRepo.get(ExternalId[MessageThread](s))}
        threads.map{ thread =>
          userThreadRepo.getNotificationByThread(userId, thread.id.get)
        }.filter(_.isDefined).map(_.get)
      }
      notificationUpdater.update(notifs)
    }
  }


}
