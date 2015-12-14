package com.keepit.eliza.controllers.internal

import com.google.inject.Inject
import com.keepit.common.controller.ElizaServiceController
import com.keepit.eliza.ElizaServiceClient._
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.discussion.{ CrossServiceMessage, Message }
import com.keepit.eliza.ElizaServiceClient
import com.keepit.eliza.commanders.ElizaDiscussionCommander
import com.keepit.eliza.model.{ ElizaMessage, MessageRepo, MessageSource, MessageThreadRepo }
import com.keepit.heimdal._
import com.keepit.model.Keep
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
        ElizaMessage.toCommon(msgId) -> CrossServiceMessage(
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
    val input = request.body.as[ElizaServiceClient.GetMessagesOnKeep.Request]
    discussionCommander.getMessagesOnKeep(input.keepId, input.fromIdOpt.map(ElizaMessage.fromCommon), input.limit).map { msgs =>
      val output = ElizaServiceClient.GetMessagesOnKeep.Response(msgs)
      Ok(Json.toJson(output))
    }
  }

  def sendMessageOnKeep() = Action.async(parse.tolerantJson) { request =>
    import SendMessageOnKeep._
    val input = request.body.as[Request]
    val contextBuilder = heimdalContextBuilder.withRequestInfo(request)
    discussionCommander.sendMessageOnKeep(input.userId, input.text, input.keepId, source = Some(MessageSource.SITE))(contextBuilder.build).map { msg =>
      val output = Response(msg)
      Ok(Json.toJson(output))
    }
  }

  def markKeepsAsReadForUser() = Action(parse.tolerantJson) { request =>
    import MarkKeepsAsReadForUser._
    val input = request.body.as[Request]
    val unreadMessagesByKeep = input.lastSeen.flatMap {
      case (keepId, msgId) => discussionCommander.markAsRead(input.userId, keepId, ElizaMessage.fromCommon(msgId)).map { unreadMsgCount => keepId -> unreadMsgCount }
    }
    val output = Response(unreadMessagesByKeep)
    Ok(Json.toJson(output))
  }

  def editMessage() = Action.async(parse.tolerantJson) { request =>
    import EditMessage._
    val input = request.body.as[Request]
    discussionCommander.editMessage(ElizaMessage.fromCommon(input.msgId), input.newText).map { msg =>
      val output = Response(msg)
      Ok(Json.toJson(output))
    }
  }

  def deleteMessage() = Action(parse.tolerantJson) { request =>
    import DeleteMessage._
    val input = request.body.as[Request]
    val msgId = ElizaMessage.fromCommon(input.msgId)
    val (msg, thread) = db.readOnlyReplica { implicit s =>
      val msg = messageRepo.get(ElizaMessage.fromCommon(input.msgId))
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
