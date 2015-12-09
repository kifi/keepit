package com.keepit.eliza.controllers.site

import com.keepit.common.crypto.{ PublicIdConfiguration, PublicId }
import com.keepit.common.json.{ TraversableFormat, KeyFormat, TupleFormat }
import com.keepit.discussion.Message
import play.api.mvc.Results.Status
import play.api.http.Status._
import com.keepit.common.core.futureExtensionOps
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
import scala.util.control.NoStackTrace
import scala.util.{ Try, Success, Failure }

sealed abstract class DiscussionFail(status: Int, msg: String) extends Exception with NoStackTrace {
  def asErrorResponse = Status(status)(Json.obj("error" -> msg))
}
object DiscussionFail {
  case class BadKeepId(kid: PublicId[Keep]) extends DiscussionFail(BAD_REQUEST, s"keep id $kid is invalid")
  case object BadPayload extends DiscussionFail(BAD_REQUEST, "could not parse a .text (string) out of the payload")
  case class CannotComment(kid: PublicId[Keep]) extends DiscussionFail(FORBIDDEN, s"insufficient permissions to comment on keep $kid")
}

class WebsiteMessagingController @Inject() (
    notificationCommander: NotificationDeliveryCommander,
    discussionCommander: ElizaDiscussionCommander,
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

    val contextBuilder = heimdalContextBuilder.withRequestInfo(request)
    (for {
      keepId <- Keep.decodePublicId(keepPubId).map(Future.successful).getOrElse(Future.failed(DiscussionFail.BadKeepId(keepPubId)))
      txt <- (request.body \ "text").asOpt[String].map(Future.successful).getOrElse(Future.failed(DiscussionFail.BadPayload))
      _ <- Future.successful(true).collectWith { case false => Future.failed(DiscussionFail.CannotComment(keepPubId)) }
      result <- discussionCommander.sendMessageOnKeep(request.userId, txt, keepId, source = Some(MessageSource.SITE))(contextBuilder.build)
    } yield NoContent).recover {
      case fail: DiscussionFail => fail.asErrorResponse
    }
  }

  def markKeepsAsRead() = UserAction(parse.tolerantJson) { request =>
    implicit val inputReads = KeyFormat.key2Reads[PublicId[Keep], PublicId[Message]]("keepId", "lastMessage")
    implicit val outputWrites = TraversableFormat.mapWrites[PublicId[Keep], Int](_.id)
    (for {
      input <- request.body.asOpt[Seq[(PublicId[Keep], PublicId[Message])]]
      readKeeps <- Try(input.map { case (pubKeepId, pubMsgId) => (Keep.decodePublicId(pubKeepId).get, ElizaMessage.fromMessageId(Message.decodePublicId(pubMsgId).get)) }).toOption
    } yield {
      val unreadMessagesByKeep = readKeeps.flatMap {
        case (keepId, msgId) => discussionCommander.markAsRead(request.userId, keepId, msgId).map { newMsgCount => Keep.publicId(keepId) -> newMsgCount }
      }.toMap
      Ok(Json.toJson(unreadMessagesByKeep))
    }).getOrElse {
      BadRequest(Json.obj("hint" -> "pass in valid keep and message ids"))
    }
  }
}
