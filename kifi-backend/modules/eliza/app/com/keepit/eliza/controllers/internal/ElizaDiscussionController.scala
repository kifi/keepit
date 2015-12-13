package com.keepit.eliza.controllers.internal

import com.keepit.common.crypto.{ PublicIdConfiguration, PublicId }
import com.keepit.common.db.Id
import com.keepit.common.json.{ TraversableFormat, KeyFormat, TupleFormat }
import com.keepit.common.logging.Logging
import com.keepit.discussion.Message
import com.keepit.eliza.commanders.{ MessageFetchingCommander, ElizaDiscussionCommander, NotificationDeliveryCommander, MessagingCommander }
import com.keepit.common.controller.ElizaServiceController
import com.keepit.common.time._
import com.keepit.eliza.model.{ ElizaMessage, MessageSource }
import com.keepit.eliza.model.ElizaMessage._
import com.keepit.heimdal._
import com.keepit.model.{ User, Keep }
import com.keepit.shoebox.ShoeboxServiceClient

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json._

import com.google.inject.Inject
import play.api.mvc.Action

import scala.concurrent.Future
import scala.util.{ Try, Success, Failure }

class ElizaDiscussionController @Inject() (
  notificationCommander: NotificationDeliveryCommander,
  discussionCommander: ElizaDiscussionCommander,
  messageFetchingCommander: MessageFetchingCommander,
  shoebox: ShoeboxServiceClient,
  implicit val publicIdConfig: PublicIdConfiguration,
  heimdalContextBuilder: HeimdalContextBuilderFactory)
    extends ElizaServiceController with Logging {

  def getMessagesOnKeep(keepId: Id[Keep], limit: Int = 10, fromRawIdOpt: Option[Long] = None) = Action.async { request =>
    val fromIdOpt = fromRawIdOpt.map(Id[Message]).map(ElizaMessage.fromCommon)
    discussionCommander.getMessagesOnKeep(keepId, fromIdOpt, limit).map { msgs =>
      Ok(Json.obj("messages" -> msgs))
    }
  }

  def sendMessageOnKeep(authorId: Id[User], keepId: Id[Keep]) = Action.async(parse.tolerantJson) { request =>
    val text = (request.body \ "text").as[String]
    val contextBuilder = heimdalContextBuilder.withRequestInfo(request)
    discussionCommander.sendMessageOnKeep(authorId, text, keepId, source = Some(MessageSource.SITE))(contextBuilder.build).map { msg =>
      Ok(Json.obj("pubId" -> Message.publicId(ElizaMessage.toCommon(msg.id.get)), "sentAt" -> msg.createdAt))
    }
  }

  def markKeepsAsRead() = Action(parse.tolerantJson) { request =>
    implicit val inputReads = KeyFormat.key2Reads[Id[Keep], Id[Message]]("keepId", "lastMessage")
    val outputWrites = TraversableFormat.mapWrites[Id[Keep], Int](_.id.toString)
    val userId = (request.body \ "user").as[Id[User]]
    val input = request.body.as[Seq[(Id[Keep], Id[Message])]]
    val unreadMessagesByKeep = input.flatMap {
      case (keepId, msgId) => discussionCommander.markAsRead(userId, keepId, ElizaMessage.fromCommon(msgId)).map { unreadMsgCount => keepId -> unreadMsgCount }
    }.toMap
    Ok(outputWrites.writes(unreadMessagesByKeep))
  }

  def deleteMessageOnKeep() = Action(parse.tolerantJson) { request =>
    val inputReads = KeyFormat.key3Reads[Id[User], Id[Keep], Id[Message]]("userId", "keepId", "messageId")
    val (userId, keepId, msgId) = request.body.as[(Id[User], Id[Keep], Id[Message])](inputReads)
    discussionCommander.deleteMessageOnKeep(userId, keepId, ElizaMessage.fromCommon(msgId)).map { _ =>
      NoContent
    }.recover {
      case fail: Exception => BadRequest(JsString(fail.getMessage))
    }.get
  }
}
