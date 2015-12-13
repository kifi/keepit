package com.keepit.eliza.controllers.internal

import com.google.inject.Inject
import com.keepit.common.controller.ElizaServiceController
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.json.{ KeyFormat, TraversableFormat }
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.discussion.{ CrossServiceMessage, Message }
import com.keepit.eliza.commanders.ElizaDiscussionCommander
import com.keepit.eliza.model.{ ElizaMessage, MessageRepo, MessageSource, MessageThreadRepo }
import com.keepit.heimdal._
import com.keepit.model.{ Keep, User }
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json._
import play.api.mvc.Action

class ElizaDiscussionController @Inject() (
  discussionCommander: ElizaDiscussionCommander,
  db: Database,
  messageRepo: MessageRepo,
  threadRepo: MessageThreadRepo,
  implicit val publicIdConfig: PublicIdConfiguration,
  heimdalContextBuilder: HeimdalContextBuilderFactory)
    extends ElizaServiceController with Logging {

  def getDiscussionsForKeeps = Action.async(parse.tolerantJson) { request =>
    val keepIds = request.body.as[Set[Id[Keep]]]
    discussionCommander.getDiscussionsForKeeps(keepIds).map { discussions =>
      Ok(Json.toJson(discussions))
    }
  }

  def getCrossServiceMessages = Action(parse.tolerantJson) { request =>
    val msgIds = request.body.as[Set[Id[Message]]].map(ElizaMessage.fromCommon)
    val crossServiceMsgs = db.readOnlyReplica { implicit s =>
      msgIds.map { msgId =>
        val msg = messageRepo.get(msgId)
        val thread = threadRepo.get(msg.thread)
        msgId -> CrossServiceMessage(
          id = ElizaMessage.toCommon(msgId),
          keep = thread.keepId,
          sentAt = msg.createdAt,
          sentBy = msg.from.asUser,
          text = msg.messageText
        )
      }.toMap
    }
    Ok(Json.toJson(crossServiceMsgs))
  }

  def getMessagesOnKeep = Action.async(parse.tolerantJson) { request =>
    implicit val inputReads = KeyFormat.key3Reads[Id[Keep], Option[Id[Message]], Int]("keepId", "fromId", "limit")
    val (keepId, fromIdOpt, limit) = request.body.as[(Id[Keep], Option[Id[Message]], Int)]
    discussionCommander.getMessagesOnKeep(keepId, fromIdOpt.map(ElizaMessage.fromCommon), limit).map { msgs =>
      Ok(Json.obj("messages" -> msgs))
    }
  }

  def sendMessageOnKeep() = Action.async(parse.tolerantJson) { request =>
    implicit val inputReads = KeyFormat.key3Reads[Id[User], String, Id[Keep]]("userId", "text", "keepId")
    val (authorId, text, keepId) = request.body.as[(Id[User], String, Id[Keep])]
    val contextBuilder = heimdalContextBuilder.withRequestInfo(request)
    discussionCommander.sendMessageOnKeep(authorId, text, keepId, source = Some(MessageSource.SITE))(contextBuilder.build).map { msg =>
      Ok(Json.obj("pubId" -> Message.publicId(ElizaMessage.toCommon(msg.id.get)), "sentAt" -> msg.createdAt))
    }
  }

  def markKeepsAsReadForUser() = Action(parse.tolerantJson) { request =>
    implicit val inputReads = KeyFormat.key2Reads[Id[Keep], Id[Message]]("keepId", "lastMessage")
    val userId = (request.body \ "user").as[Id[User]]
    val input = request.body.as[Seq[(Id[Keep], Id[Message])]]
    val unreadMessagesByKeep = input.flatMap {
      case (keepId, msgId) => discussionCommander.markAsRead(userId, keepId, ElizaMessage.fromCommon(msgId)).map { unreadMsgCount => keepId -> unreadMsgCount }
    }.toMap

    val outputWrites = TraversableFormat.mapWrites[Id[Keep], Int](_.id.toString)
    Ok(outputWrites.writes(unreadMessagesByKeep))
  }

  def editMessage() = Action(parse.tolerantJson) { request =>
    val inputReads = KeyFormat.key2Reads[Id[Message], String]("messageId", "newText")
    val (msgId, newText) = request.body.as[(Id[Message], String)](inputReads)
    discussionCommander.editMessage(ElizaMessage.fromCommon(msgId), newText)
    NoContent
  }

  def deleteMessage() = Action(parse.tolerantJson) { request =>
    val inputReads = KeyFormat.key1Reads[Id[Message]]("messageId")
    val msgId = ElizaMessage.fromCommon(request.body.as[Id[Message]](inputReads))
    val (msg, thread) = db.readOnlyReplica { implicit s =>
      val msg = messageRepo.get(msgId)
      val thread = threadRepo.get(msg.thread)
      (msg, thread)
    }
    // This is sort of messy because of the type signature on `deleteMessageOnKeep`
    // As soon as possible we should port over to just deleting the messages, and trusting that whoever
    // hits this endpoint knows what they're doing
    discussionCommander.deleteMessageOnKeep(msg.from.asUser.get, thread.keepId.get, msgId).map { _ =>
      NoContent
    }.recover {
      case fail: Exception => BadRequest(JsString(fail.getMessage))
    }.get
  }
}
