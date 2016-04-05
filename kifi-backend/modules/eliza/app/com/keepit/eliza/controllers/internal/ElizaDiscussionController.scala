package com.keepit.eliza.controllers.internal

import com.google.inject.Inject
import com.keepit.common.controller.ElizaServiceController
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.slick.Database
import com.keepit.common.db.{ Id, SequenceNumber }
import com.keepit.common.logging.Logging
import com.keepit.eliza.ElizaServiceClient._
import com.keepit.model.{ KeepRecipients, ElizaFeedFilter, User, Keep }
import com.keepit.discussion.Message
import com.keepit.eliza.commanders.ElizaDiscussionCommander
import com.keepit.eliza.model._
import com.keepit.heimdal._
import org.joda.time.DateTime
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
  nonUserThreadRepo: NonUserThreadRepo,
  implicit val publicIdConfig: PublicIdConfiguration,
  heimdalContextBuilder: HeimdalContextBuilderFactory)
    extends ElizaServiceController with Logging {

  def getDiscussionsForKeeps = Action.async(parse.tolerantJson) { request =>
    import GetDiscussionsForKeeps._
    val input = request.body.as[Request]
    discussionCommander.getDiscussionsForKeeps(input.keepIds, input.maxMessagesShown).map { discussions =>
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

  def getCrossServiceKeepActivity = Action.async(parse.tolerantJson) { request =>
    import GetCrossServiceKeepActivity._
    val input = request.body.as[Request]
    discussionCommander.getCrossServiceKeepActivity(input.keepIds, input.eventsBefore, input.maxEventsPerKeep).map { activityByKeep =>
      Ok(Json.toJson(Response(activityByKeep)))
    }
  }

  def saveKeepEvent = Action.async(parse.tolerantJson) { request =>
    import SaveKeepEvent._
    val input = request.body.as[Request]
    discussionCommander.saveKeepEvent(input.keepId, input.userId, input.event).map(_ => NoContent)
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
    input.source.foreach { src => contextBuilder += ("source", src.value) }
    discussionCommander.sendMessage(input.userId, input.text, input.keepId, source = input.source)(contextBuilder.build).map { msg =>
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
    discussionCommander.editParticipantsOnKeep(input.keepId, input.editor, input.newUsers, input.newLibraries, input.source).map { allUsers =>
      val output = Response(allUsers)
      Ok(Json.toJson(output))
    }
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

  def getEmailParticipantsForKeeps() = Action(parse.tolerantJson) { request =>
    import GetEmailParticipantsForKeep._
    val keepIds = request.body.as[Request].keepIds
    val emailParticipantsByKeepIds = discussionCommander.getEmailParticipantsForKeeps(keepIds)
    Ok(Json.toJson(Response(emailParticipantsByKeepIds)))
  }

  def getInitialRecipientsByKeepId() = Action.async(parse.tolerantJson) { request =>
    import GetInitialRecipientsByKeepId._
    val keepIds = request.body.as[Request].keepIds
    db.readOnlyMasterAsync { implicit s =>
      val threads = threadRepo.getByKeepIds(keepIds, excludeState = None)
      val recipientsByKeepId = threads.map {
        case (keepId, thread) =>
          def addedNearStart(time: DateTime) = time.minusSeconds(1).getMillis < thread.createdAt.getMillis
          val firstUsers = thread.participants.userParticipants.collect { case (uid, added) if uid != thread.startedBy && addedNearStart(added) => uid }
          val firstNonUsers = thread.participants.nonUserParticipants.collect { case (NonUserEmailParticipant(email), added) if addedNearStart(added) => email }
          keepId -> KeepRecipients(libraries = Set.empty, firstNonUsers.toSet, firstUsers.toSet)
      }
      Ok(Json.toJson(Response(recipientsByKeepId)))
    }
  }

  def pageSystemMessages(fromId: Id[Message], pageSize: Int) = Action { request =>
    val msgs = db.readOnlyMaster(implicit s => messageRepo.pageSystemMessages(ElizaMessage.fromCommonId(fromId), pageSize)).map(ElizaMessage.toCrossServiceMessage)
    Ok(Json.obj("messages" -> msgs))
  }
}
