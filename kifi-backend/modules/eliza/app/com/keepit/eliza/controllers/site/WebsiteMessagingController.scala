package com.keepit.eliza.controllers.site

import com.keepit.common.crypto.{ PublicIdConfiguration, PublicId }
import com.keepit.discussion.Message
import com.keepit.eliza.commanders.{ ElizaDiscussionCommander, NotificationDeliveryCommander, MessagingCommander }
import com.keepit.common.controller.{ ElizaServiceController, UserActions, UserActionsHelper }
import com.keepit.common.time._
import com.keepit.eliza.model.{ ElizaMessage, MessageSource }
import com.keepit.heimdal._
import com.keepit.model.Keep
import com.keepit.shoebox.ShoeboxServiceClient

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json._

import com.google.inject.Inject

import scala.concurrent.Future
import scala.util.{ Success, Failure }

class WebsiteMessagingController @Inject() (
    notificationCommander: NotificationDeliveryCommander,
    discussionCommander: ElizaDiscussionCommander,
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
    Keep.decodePublicId(keepPubId) match {
      case Failure(err) => Future.successful(BadRequest(Json.obj("error" -> "invalid_id")))
      case Success(keepId) =>
        (request.body \ "text").asOpt[String] match {
          case None => Future.successful(BadRequest(Json.obj("error" -> "missing_text")))
          case Some(text) =>
            shoebox.canCommentOnKeep(request.userId, keepId).flatMap { canComment =>
              if (canComment) {
                val contextBuilder = heimdalContextBuilder.withRequestInfo(request)

                discussionCommander.sendMessageOnKeep(request.userId, text, keepId, source = Some(MessageSource.SITE))(contextBuilder.build).map { _ =>
                  NoContent
                }
              } else Future.successful(Forbidden)
            }
        }
    }
  }
}
