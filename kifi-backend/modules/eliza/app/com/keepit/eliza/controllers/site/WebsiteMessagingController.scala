package com.keepit.eliza.controllers.site

import com.keepit.common.crypto.{ PublicIdConfiguration, PublicId }
import com.keepit.common.db.Id
import com.keepit.common.json.{ TraversableFormat, KeyFormat, TupleFormat }
import com.keepit.discussion.Message
import com.keepit.eliza.commanders.{ MessageFetchingCommander, ElizaDiscussionCommander, NotificationDeliveryCommander, MessagingCommander }
import com.keepit.common.controller.{ ElizaServiceController, UserActions, UserActionsHelper }
import com.keepit.common.time._
import com.keepit.eliza.model.{ ElizaMessage, MessageSource }
import com.keepit.eliza.model.ElizaMessage._
import com.keepit.heimdal._
import com.keepit.model.Keep
import com.keepit.shoebox.ShoeboxServiceClient

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json._

import com.google.inject.Inject

import scala.concurrent.Future
import scala.util.{ Try, Success, Failure }

class WebsiteMessagingController @Inject() (
    notificationCommander: NotificationDeliveryCommander,
    discussionCommander: ElizaDiscussionCommander,
    messageFetchingCommander: MessageFetchingCommander,
    shoebox: ShoeboxServiceClient,
    val userActionsHelper: UserActionsHelper,
    implicit val publicIdConfig: PublicIdConfiguration,
    heimdalContextBuilder: HeimdalContextBuilderFactory) extends UserActions with ElizaServiceController {

  def getNotifications(howMany: Int, before: Option[String]) = UserAction.async { request =>
    val noticesFuture = before match {
      case Some(time) =>
        notificationCommander.getSendableNotificationsBefore(request.userId, parseStandardTime(time), howMany.toInt, includeUriSummary = false)
      case None =>
        notificationCommander.getLatestSendableNotifications(request.userId, howMany.toInt, includeUriSummary = false)
    }
    noticesFuture.map { notices =>
      val numUnreadUnmuted = notificationCommander.getTotalUnreadUnmutedCount(request.userId)
      Ok(Json.arr("notifications", notices.map(_.obj), numUnreadUnmuted))
    }
  }
}
