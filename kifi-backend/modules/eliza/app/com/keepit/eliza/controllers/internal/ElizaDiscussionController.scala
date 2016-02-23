package com.keepit.eliza.controllers.internal

import com.google.inject.Inject
import com.keepit.common.controller.ElizaServiceController
import com.keepit.eliza.ElizaServiceClient._
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.{ SequenceNumber, Id }
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.model.{ ElizaFeedFilter, User, Keep }
import com.keepit.discussion.Message
import com.keepit.eliza.commanders.ElizaDiscussionCommander
import com.keepit.eliza.model._
import com.keepit.heimdal._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json._
import play.api.mvc.Action

import scala.util.Try

class ElizaDiscussionController @Inject() (
  discussionCommander: ElizaDiscussionCommander,
  db: Database,
  messageRepo: MessageRepo,
  threadRepo: MessageThreadRepo,
  userThreadRepo: UserThreadRepo,
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
        val msg = messageRepo.get(ElizaMessage.fromCommonId(msgId))
        msgId -> ElizaMessage.toCrossServiceMessage(msg)
      }.toMap
    }
    val output = Response(crossServiceMsgs)
    Ok(Json.toJson(output))
  }

  def getMessagesOnKeep = Action.async(parse.tolerantJson) { request =>
    import GetMessagesOnKeep._
    val input = request.body.as[Request]
    discussionCommander.getMessagesOnKeep(input.keepId, input.fromIdOpt.map(ElizaMessage.fromCommonId), input.limit).map { msgs =>
      val output = Response(msgs)
      Ok(Json.toJson(output))
    }
  }

  def getMessageCountsForKeeps = Action(parse.tolerantJson) { request =>
    import GetMessageCountsForKeeps._
    val input = request.body.as[Request]
    val countByKeepId = db.readOnlyMaster(implicit s => messageRepo.getAllMessageCounts(input.keepIds))
    Ok(Json.toJson(Response(countByKeepId)))
  }
  def getChangedMessagesFromKeeps = Action(parse.tolerantJson) { request =>
    import GetChangedMessagesFromKeeps._
    val input = request.body.as[Request]
    val changedMessages = db.readOnlyMaster { implicit session =>
      messageRepo.getForKeepsBySequenceNumber(input.keepIds, ElizaMessage.fromCommonSeq(input.seq))
    }.map(ElizaMessage.toCrossServiceMessage)
    Ok(Json.toJson(Response(changedMessages)))
  }

  def sendMessageOnKeep() = Action.async(parse.tolerantJson) { request =>
    import SendMessageOnKeep._
    val input = request.body.as[Request]
    val contextBuilder = heimdalContextBuilder.withRequestInfo(request)
    discussionCommander.sendMessage(input.userId, input.text, input.keepId, source = Some(MessageSource.SITE))(contextBuilder.build).map { msg =>
      val output = Response(msg)
      Ok(Json.toJson(output))
    }
  }

  def markKeepsAsReadForUser() = Action(parse.tolerantJson) { request =>
    import MarkKeepsAsReadForUser._
    val input = request.body.as[Request]
    val unreadMessagesByKeep = input.lastSeen.flatMap {
      case (keepId, msgId) => discussionCommander.markAsRead(input.userId, keepId, ElizaMessage.fromCommonId(msgId)).map { unreadMsgCount => keepId -> unreadMsgCount }
    }
    val output = Response(unreadMessagesByKeep)
    Ok(Json.toJson(output))
  }

  def editMessage() = Action.async(parse.tolerantJson) { request =>
    import EditMessage._
    val input = request.body.as[Request]
    discussionCommander.editMessage(ElizaMessage.fromCommonId(input.msgId), input.newText).map { msg =>
      val output = Response(msg)
      Ok(Json.toJson(output))
    }
  }

  def deleteMessage() = Action(parse.tolerantJson) { request =>
    import DeleteMessage._
    val input = request.body.as[Request]
    val msgId = ElizaMessage.fromCommonId(input.msgId)
    discussionCommander.deleteMessage(msgId)
    NoContent
  }
  def editParticipantsOnKeep() = Action.async(parse.tolerantJson) { request =>
    import EditParticipantsOnKeep._
    val input = request.body.as[Request]
    discussionCommander.editParticipantsOnKeep(input.keepId, input.editor, input.newUsers).map { allUsers =>
      val output = Response(allUsers)
      Ok(Json.toJson(output))
    }
  }
  def deleteThreadsForKeeps() = Action(parse.tolerantJson) { request =>
    import DeleteThreadsForKeeps._
    val input = request.body.as[Request]
    discussionCommander.deleteThreadsForKeeps(input.keepIds)
    NoContent
  }

  def keepHasAccessToken(keepId: Id[Keep], accessToken: String) = Action { request =>
    val hasToken = Try(ThreadAccessToken(accessToken)).map { token =>
      discussionCommander.keepHasAccessToken(keepId, token)
    }.getOrElse(false)

    Ok(Json.obj("hasToken" -> hasToken))
  }

  def getMessagesChanged(seqNum: SequenceNumber[Message], fetchSize: Int) = Action { request =>
    val messages = db.readOnlyMaster { implicit session =>
      val elizaMessages = messageRepo.getBySequenceNumber(ElizaMessage.fromCommonSeq(seqNum), fetchSize)
      elizaMessages.map(ElizaMessage.toCrossServiceMessage)
    }
    Ok(Json.toJson(messages))
  }

  def getElizaKeepStream(userId: Id[User], limit: Int, beforeId: Option[Long], filter: ElizaFeedFilter) = Action {
    val beforeKeepId = beforeId.map(Id[Keep])
    val lastActivityByKeepId = db.readOnlyMaster(implicit s => userThreadRepo.getThreadStream(userId, limit, beforeKeepId, filter))
    Ok(Json.obj("lastActivityByKeepId" -> Json.toJson(lastActivityByKeepId)))
  }
}
