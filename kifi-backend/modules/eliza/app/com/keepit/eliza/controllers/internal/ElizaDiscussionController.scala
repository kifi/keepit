package com.keepit.eliza.controllers.internal

import com.google.inject.Inject
import com.keepit.common.controller.ElizaServiceController
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.slick.Database
import com.keepit.common.db.{ Id, SequenceNumber }
import com.keepit.common.logging.Logging
import com.keepit.common.core.iterableExtensionOps
import com.keepit.common.mail.BasicContact
import com.keepit.eliza.ElizaServiceClient._
import com.keepit.model.{ KeepRecipientsDiff, KeepRecipients, ElizaFeedFilter, User, Keep }
import com.keepit.discussion.Message
import com.keepit.eliza.commanders.{ NotificationDeliveryCommander, ElizaDiscussionCommander }
import com.keepit.eliza.model._
import com.keepit.heimdal._
import org.joda.time.DateTime
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json._
import play.api.mvc.Action

import scala.util.{ Success, Try }

class ElizaDiscussionController @Inject() (
  discussionCommander: ElizaDiscussionCommander,
  notifDeliveryCommander: NotificationDeliveryCommander,
  db: Database,
  messageRepo: MessageRepo,
  threadRepo: MessageThreadRepo,
  userThreadRepo: UserThreadRepo,
  nonUserThreadRepo: NonUserThreadRepo,
  implicit val publicIdConfig: PublicIdConfiguration,
  heimdalContextBuilder: HeimdalContextBuilderFactory)
    extends ElizaServiceController with Logging {

  def getCrossServiceDiscussionsForKeeps = Action(parse.tolerantJson) { request =>
    import GetCrossServiceDiscussionsForKeeps._
    val input = request.body.as[Request]
    val discussions = discussionCommander.getCrossServiceDiscussionsForKeeps(input.keepIds, input.fromTime, input.maxMessagesShown)
    val output = Response(discussions)
    Ok(Json.toJson(output))
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
    val countByKeepId = db.readOnlyMaster(implicit s => messageRepo.getNumCommentsByKeep(input.keepIds))
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
    implicit val context = {
      val contextBuilder = heimdalContextBuilder.withRequestInfo(request)
      input.source.foreach { src => contextBuilder += ("source", src.value) }
      contextBuilder.build
    }
    implicit val time = input.time
    discussionCommander.sendMessage(input.userId, input.text, input.keepId, source = input.source)(time = time, context = context).map { msg =>
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
  def modifyRecipientsAndSendEvent() = Action.async(parse.tolerantJson) { request =>
    import ModifyRecipientsAndSendEvent._
    val input = request.body.as[Request]
    implicit val ctxt = HeimdalContext.empty
    log.info(s"[EDCtrlr-MRASE] Handling an event for ${input.keepId} from ${input.userAttribution}")
    discussionCommander.modifyRecipientsForKeep(input.keepId, input.userAttribution, input.diff, input.source).andThen {
      case Success((thread, diff)) => input.notifEvent.foreach { event =>
        notifDeliveryCommander.notifyThreadAboutParticipantDiff(input.userAttribution, input.diff, thread, event)
      }
    }.map(_ => NoContent)
  }
  def internEmptyThreadsForKeeps() = Action(parse.tolerantJson) { request =>
    import InternEmptyThreadsForKeeps._
    implicit val context = heimdalContextBuilder().build
    val keeps = request.body.as[Request].keeps
    require(keeps.forall(_.isActive), "internEmptyThreads called with a dead keep")
    val existingThreads = db.readOnlyMaster { implicit s =>
      threadRepo.getByKeepIds(keeps.map(_.id).toSet)
    }
    val (oldKeeps, newKeeps) = keeps.partition(k => existingThreads.contains(k.id))
    val existingKeepsWithThreads = oldKeeps.flatAugmentWith(k => existingThreads.get(k.id))
    db.readWrite { implicit s =>
      existingKeepsWithThreads.foreach {
        case (keep, thread) => (keep.users -- thread.participants.allUsers).foreach { u =>
          userThreadRepo.intern(UserThread.forMessageThread(thread)(u))
        }
      }
      newKeeps.foreach { k =>
        k.owner.foreach { owner =>
          discussionCommander.internThreadForKeep(k, owner)
        }
      }
    }
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

  def getInitialRecipientsByKeepId() = Action.async(parse.tolerantJson) { request =>
    import GetInitialRecipientsByKeepId._
    val keepIds = request.body.as[Request].keepIds
    db.readOnlyMasterAsync { implicit s =>
      val threads = threadRepo.getByKeepIds(keepIds, excludeState = None)
      val recipientsByKeepId = threads.map {
        case (keepId, thread) =>
          def addedNearStart(time: DateTime) = time.minusSeconds(1).getMillis <= thread.createdAt.getMillis
          val firstUsers = thread.participants.userParticipants.collect { case (uid, added) if addedNearStart(added) => uid }
          val firstNonUsers = thread.participants.emailParticipants.collect { case (EmailParticipant(email), added) if addedNearStart(added) => email }
          keepId -> KeepRecipients(libraries = Set.empty, firstNonUsers.toSet, firstUsers.toSet)
      }
      Ok(Json.toJson(Response(recipientsByKeepId)))
    }
  }

  def pageSystemMessages(fromId: Id[Message], pageSize: Int) = Action { request =>
    val msgs = db.readOnlyMaster(implicit s => messageRepo.pageSystemMessages(ElizaMessage.fromCommonId(fromId), pageSize)).map(ElizaMessage.toCrossServiceMessage)
    Ok(Json.toJson(msgs))
  }

  def rpbTest() = Action(parse.tolerantJson) { implicit request =>
    import RPBTest._
    val input = request.body.as[Request]
    val ans = db.readOnlyMaster { implicit s =>
      messageRepo.getRecentByKeeps(input.keepIds, limitPerKeep = input.numPerKeep)
    }
    Ok(responseFormat.writes(Response(ans.mapValues(_.map(ElizaMessage.toCommonId)))))
  }
}
