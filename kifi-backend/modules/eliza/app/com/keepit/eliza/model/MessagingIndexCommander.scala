package com.keepit.eliza.model

import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.eliza.util.MessageFormatter
import com.keepit.search.index.message.{ ThreadContent, FULL }
import com.keepit.common.db.{ Id, SequenceNumber }
import com.keepit.common.db.slick.Database
import com.keepit.model.{ Keep, User }
import com.keepit.shoebox.ShoeboxServiceClient
import com.google.inject.Inject
import com.keepit.social.BasicUserLikeEntity

import scala.concurrent.Future

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.common.logging.Logging
import com.keepit.common.healthcheck.AirbrakeNotifier

//This thing isn't really very efficient, but it should do for now, on account of most threads being very short -Stephen
class MessagingIndexCommander @Inject() (
    messageRepo: MessageRepo,
    threadRepo: MessageThreadRepo,
    db: Database,
    shoebox: ShoeboxServiceClient,
    airbrake: AirbrakeNotifier,
    implicit val publicIdConfig: PublicIdConfiguration) extends Logging {

  private def getMessages(fromId: Id[ElizaMessage], toId: Id[ElizaMessage], maxId: Id[ElizaMessage]): Seq[ElizaMessage] = {
    val messages = db.readOnlyReplica { implicit session => messageRepo.getFromIdToId(fromId, toId) }
    val filteredMessages = messages.filter(!_.from.isSystem)
    if (filteredMessages.length > 1 || toId.id >= maxId.id) filteredMessages
    else getMessages(fromId, Id[ElizaMessage](toId.id + 100), maxId)
  }

  private def getThreadContentsForThreadWithSequenceNumber(keepId: Id[Keep], seq: SequenceNumber[ThreadContent]): Future[ThreadContent] = {
    log.info(s"getting content for thread $keepId seq $seq")
    val thread = db.readOnlyReplica { implicit session => threadRepo.getByKeepId(keepId).get }
    val threadId = thread.id.get
    val userParticipants: Seq[Id[User]] = thread.participants.allUsers.toSeq
    val participantBasicUsersFuture = shoebox.getBasicUsers(userParticipants)
    val participantBasicNonUsers = thread.participants.allNonUsers.map(nu => BasicUserLikeEntity(EmailParticipant.toBasicNonUser(nu)))

    val messages: Seq[ElizaMessage] = db.readOnlyReplica { implicit session =>
      messageRepo.get(keepId, 0)
    }.filterNot(_.from.isSystem).sortBy(-_.createdAt.getMillis)

    val digest = try {
      MessageFormatter.toText(messages.head.messageText).slice(0, 255)
    } catch {
      case e: Throwable =>
        airbrake.notify(e)
        messages.head.messageText
    }
    val content = messages.map { m =>
      try {
        MessageFormatter.toText(m.messageText)
      } catch {
        case e: Throwable =>
          airbrake.notify(e)
          m.messageText
      }
    }

    participantBasicUsersFuture.map { participantBasicUsers =>
      ThreadContent(
        mode = FULL,
        id = Id[ThreadContent](threadId.id),
        seq = seq,
        participants = participantBasicUsers.values.toSeq.map(BasicUserLikeEntity.apply) ++ participantBasicNonUsers,
        updatedAt = messages.head.createdAt,
        url = thread.url,
        keepId = thread.pubKeepId,
        pageTitleOpt = thread.pageTitle,
        digest = digest,
        content = content,
        participantIds = userParticipants
      )
    }
  }

  def getThreadContentsForMessagesFromIdToId(fromId: Id[ElizaMessage], toId: Id[ElizaMessage]): Future[Seq[ThreadContent]] = {
    val maxMessageId = db.readOnlyReplica { implicit session => messageRepo.getMaxId() }
    log.info(s"trying to get messages from $fromId, to $toId max $maxMessageId")
    val allMessages = getMessages(fromId, toId, maxMessageId)
    log.info(s"got messages ${allMessages.map(_.id.get).mkString(",")}")
    val messagesByKeep = allMessages.groupBy(_.keepId)
    Future.sequence(messagesByKeep.toSeq.map {
      case (keepId, messages) =>
        getThreadContentsForThreadWithSequenceNumber(keepId, SequenceNumber(messages.map(_.id.get.id).max))
    })
  }

}
