package com.keepit.eliza.model

import com.keepit.eliza.util.MessageFormatter
import com.keepit.search.message.{ThreadContent, FULL}
import com.keepit.common.db.{Id, SequenceNumber}
import com.keepit.common.db.slick.Database
import com.keepit.model.User
import com.keepit.shoebox.ShoeboxServiceClient
import com.google.inject.Inject

import scala.concurrent.Future

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.common.logging.Logging
import com.keepit.common.healthcheck.AirbrakeNotifier


//This thing isn't really very efficient, but it should do for now, on account of most threads being very short -Stephen
class MessagingIndexCommander @Inject() (
    messageRepo : MessageRepo,
    threadRepo : MessageThreadRepo,
    db: Database,
    shoebox: ShoeboxServiceClient,
    airbrake: AirbrakeNotifier
  ) extends Logging {

  private def getMessages(fromId: Id[Message], toId: Id[Message], maxId: Id[Message]) : Seq[Message] = {
    val messages = db.readOnly{ implicit session => messageRepo.getFromIdToId(fromId, toId) }
    val filteredMessages = messages.filter(!_.from.isSystem)
    if (filteredMessages.length > 1 || toId.id >= maxId.id) filteredMessages
    else getMessages(fromId, Id[Message](toId.id+100), maxId)
  }

  private def getThreadContentsForThreadWithSequenceNumber(threadId: Id[MessageThread], seq: SequenceNumber[ThreadContent]): Future[ThreadContent] = {
    log.info(s"getting content for thread $threadId seq $seq")
    val thread = db.readOnly{ implicit session => threadRepo.get(threadId) }
    val participants : Seq[Id[User]] = thread.participants.map(_.allUsers).getOrElse(Set[Id[User]]()).toSeq
    val participantBasicUsersFuture = shoebox.getBasicUsers(participants)

    val messages : Seq[Message] = db.readOnly{ implicit session =>
      messageRepo.get(threadId, 0)
    } sortWith { case (m1, m2) =>
      m1.createdAt.isAfter(m2.createdAt)
    } filter { message =>
      !message.from.isSystem
    }

    val digest = try {
      MessageFormatter.toText(messages.head.messageText).slice(0,255)
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

    participantBasicUsersFuture.map{ participantBasicUsers =>
      ThreadContent(
        mode = FULL,
        id = Id[ThreadContent](threadId.id),
        seq = seq,
        participants = participantBasicUsers.values.toSeq,
        updatedAt = messages.head.createdAt,
        url = thread.url.getOrElse(""),
        threadExternalId = thread.externalId.id,
        pageTitleOpt = thread.pageTitle,
        digest = digest,
        content = content,
        participantIds = participants
      )
    }
  }

  def getThreadContentsForMessagesFromIdToId(fromId: Id[Message], toId: Id[Message]) : Future[Seq[ThreadContent]] = {
    val maxMessageId = db.readOnly{ implicit session => messageRepo.getMaxId()}
    log.info(s"trying to get messages from $fromId, to $toId max $maxMessageId")
    val allMessages = getMessages(fromId, toId, maxMessageId)
    log.info(s"got messages ${allMessages.map(_.id.get).mkString(",")}")
    val threadMessages = allMessages.groupBy(_.thread)
    Future.sequence(threadMessages.toSeq.map{ case (threadId, messages) =>
      getThreadContentsForThreadWithSequenceNumber(threadId, SequenceNumber(messages.map(_.id.get.id).max))
    })
  }

}
