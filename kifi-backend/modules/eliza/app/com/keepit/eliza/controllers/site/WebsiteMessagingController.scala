package com.keepit.eliza.controllers.site

import com.keepit.common.crypto.{ PublicIdConfiguration, PublicId }
import com.keepit.discussion.Message
import com.keepit.eliza.commanders.{ ElizaDiscussionCommander, NotificationDeliveryCommander, MessagingCommander }
import com.keepit.common.controller.{ ElizaServiceController, UserActions, UserActionsHelper }
import com.keepit.common.time._
import com.keepit.eliza.model.{ ElizaMessage, MessageSource }
import com.keepit.heimdal._
import com.keepit.model.Keep

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json._

import com.google.inject.Inject

import scala.concurrent.Future
import scala.util.{ Success, Failure }

class WebsiteMessagingController @Inject() (
    messagingCommander: MessagingCommander,
    notificationCommander: NotificationDeliveryCommander,
    discussionCommander: ElizaDiscussionCommander,
    val userActionsHelper: UserActionsHelper,
    implicit val publicIdConfig: PublicIdConfiguration,
    heimdalContextBuilder: HeimdalContextBuilderFactory) extends UserActions with ElizaServiceController {

  def getNotifications(howMany: Int, before: Option[String]) = UserAction.async { request =>
    val noticesFuture = before match {
      case Some(before) =>
        notificationCommander.getSendableNotificationsBefore(request.userId, parseStandardTime(before), howMany.toInt, includeUriSummary = false)
      case None =>
        notificationCommander.getLatestSendableNotifications(request.userId, howMany.toInt, includeUriSummary = false)
    }
    noticesFuture.map { notices =>
      val numUnreadUnmuted = notificationCommander.getTotalUnreadUnmutedCount(request.userId)
      Ok(Json.arr("notifications", notices.map(_.obj), numUnreadUnmuted))
    }
  }

  def getMessagesOnKeep(keepPubId: PublicId[Keep], limit: Int = 10, fromPubIdOpt: Option[String] = None) = UserAction.async { request =>
    val keepIdTry = Keep.decodePublicId(keepPubId)
    val fromIdOptTry = fromPubIdOpt.filter(_.nonEmpty) match {
      case None => Success(None)
      case Some(fromPubId) => Message.decodePublicId(PublicId[Message](fromPubId)).map(msgId => Some(ElizaMessage.fromMessageId(msgId)))
    }
    (for {
      keepId <- keepIdTry
      fromIdOpt <- fromIdOptTry
    } yield {
      discussionCommander.getMessagesOnKeep(keepId, fromIdOpt, limit).map { msgs =>
        Ok(Json.obj("messages" -> msgs))
      }
    }).getOrElse {
      Future.successful(BadRequest(Json.obj("hint" -> "pass in a valid keepId and fromId")))
    }
  }

  def sendMessageOnKeep(keepPubId: PublicId[Keep]) = UserAction.async(parse.tolerantJson) { request =>
    (for {
      keepId <- Keep.decodePublicId(keepPubId).toOption
      txt <- (request.body \ "text").asOpt[String]
    } yield {
      val contextBuilder = heimdalContextBuilder.withRequestInfo(request)
      discussionCommander.sendMessageOnKeep(request.userId, txt, keepId, source = Some(MessageSource.SITE))(contextBuilder.build).map { _ =>
        NoContent
      }
    }).getOrElse {
      Future.successful(BadRequest(Json.obj("hint" -> "pass in a valid keep id and make sure your request has .text string")))
    }
  }
}
