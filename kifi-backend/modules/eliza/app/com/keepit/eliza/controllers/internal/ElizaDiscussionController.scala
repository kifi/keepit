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
import com.keepit.eliza.model._
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
    import GetDiscussionsForKeeps._
    val input = request.body.as[Request]
    discussionCommander.getDiscussionsForKeeps(input.keepIds).map { discussions =>
      val output = Response(discussions)
      Ok(Json.toJson(output))
    }
  }

  def getCrossServiceMessages = Action(parse.tolerantJson) { request =>
    import GetCrossServiceMessages._
    val input = request.body.as[Request]
    val crossServiceMsgs = db.readOnlyReplica { implicit s =>
      input.msgIds.map { msgId =>
        val msg = messageRepo.get(ElizaMessage.fromCommon(msgId))
        val thread = threadRepo.get(msg.thread)
        msgId -> CrossServiceMessage(
          id = msgId,
          keep = thread.keepId,
          sentAt = msg.createdAt,
          sentBy = msg.from.asUser,
          text = msg.messageText
        )
      }.toMap
    }
    val output = Response(crossServiceMsgs)
    Ok(Json.toJson(output))
  }

  def getMessagesOnKeep = Action.async(parse.tolerantJson) { request =>
    import GetMessagesOnKeep._
    val input = request.body.as[Request]
    discussionCommander.getMessagesOnKeep(input.keepId, input.fromIdOpt.map(ElizaMessage.fromCommon), input.limit).map { msgs =>
      val output = Response(msgs)
      Ok(Json.toJson(output))
    }
  }

  def sendMessageOnKeep() = Action.async(parse.tolerantJson) { request =>
    import SendMessageOnKeep._
    val input = request.body.as[Request]
    val contextBuilder = heimdalContextBuilder.withRequestInfo(request)
    discussionCommander.sendMessage(input.userId, input.text, KeepId(input.keepId), source = Some(MessageSource.SITE))(contextBuilder.build).map { msg =>
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
    discussionCommander.deleteMessage(msgId)
    NoContent
  }

  def rpbGetThread() = Action(parse.tolerantJson) { request =>
    import RPBGetThread._
    val input = request.body.as[Request]
    db.readOnlyMaster { implicit s =>
      val thread = threadRepo.get(Id[MessageThread](input.threadId))
      val output = Response(thread.startedBy, thread.pageTitle, thread.url, thread.createdAt)
      Ok(Json.toJson(output))
    }
  }
  def rpbConnectKeep() = Action(parse.tolerantJson) { request =>
    import RPBConnectKeep._
    val input = request.body.as[Request]
    db.readWrite { implicit s =>
      val thread = threadRepo.get(Id[MessageThread](input.threadId))
      assert(thread.keepId.isEmpty)
      threadRepo.save(thread.withKeepId(input.keepId))
      NoContent
    }
  }
}
