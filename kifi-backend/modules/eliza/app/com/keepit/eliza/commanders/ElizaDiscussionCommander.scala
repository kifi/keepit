package com.keepit.eliza.commanders

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.core.futureExtensionOps
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.slick.Database
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.discussion.{ Discussion, Message }
import com.keepit.eliza.model._
import com.keepit.heimdal.HeimdalContext
import com.keepit.model._
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.social.BasicUserLikeEntity
import play.api.libs.json.JsNull

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success, Try }

@ImplementedBy(classOf[ElizaDiscussionCommanderImpl])
trait ElizaDiscussionCommander {
  def getMessagesOnKeep(keepId: Id[Keep], fromIdOpt: Option[Id[ElizaMessage]], limit: Int): Future[Seq[Message]]

  def getDiscussionForKeep(keepId: Id[Keep]): Future[Discussion]
  def getDiscussionsForKeeps(keepIds: Set[Id[Keep]]): Future[Map[Id[Keep], Discussion]]
  def sendMessageOnKeep(userId: Id[User], txt: String, keepId: Id[Keep], source: Option[MessageSource] = None)(implicit context: HeimdalContext): Future[ElizaMessage]
  def markAsRead(userId: Id[User], keepId: Id[Keep], msgId: Id[ElizaMessage]): Option[Int]
  def editMessage(messageId: Id[ElizaMessage], newText: String): ElizaMessage
  def deleteMessageOnKeep(userId: Id[User], keepId: Id[Keep], messageId: Id[ElizaMessage]): Try[Unit]
}

@Singleton
class ElizaDiscussionCommanderImpl @Inject() (
  db: Database,
  messageThreadRepo: MessageThreadRepo,
  userThreadRepo: UserThreadRepo,
  messageRepo: MessageRepo,
  messagingCommander: MessagingCommander,
  clock: Clock,
  airbrake: AirbrakeNotifier,
  shoebox: ShoeboxServiceClient,
  implicit val defaultContext: ExecutionContext,
  implicit val publicIdConfig: PublicIdConfiguration)
    extends ElizaDiscussionCommander with Logging {

  val MESSAGES_TO_INCLUDE = 8

  private def externalizeMessages(emsgs: Seq[ElizaMessage]): Future[Map[Id[ElizaMessage], Message]] = {
    val users = emsgs.flatMap(_.from.asUser)
    val nonUsers = emsgs.flatMap(_.from.asNonUser)
    val basicUsersFut = shoebox.getBasicUsers(users)
    val basicNonUsersFut = Future.successful(nonUsers.map(nu => nu -> NonUserParticipant.toBasicNonUser(nu)).toMap)
    for {
      basicUsers <- basicUsersFut
      basicNonUsers <- basicNonUsersFut
    } yield emsgs.flatMap { em =>
      val msgId = Message.publicId(ElizaMessage.toCommon(em.id.get)) // this is a hack to expose Id[ElizaMessage] without exposing ElizaMessage itself
      val senderOpt: Option[BasicUserLikeEntity] = em.from.fold(
        None,
        { uid => Some(BasicUserLikeEntity(basicUsers(uid))) },
        { nu => Some(BasicUserLikeEntity(basicNonUsers(nu))) }
      )
      senderOpt.map { sender =>
        em.id.get -> Message(
          pubId = msgId,
          sentAt = em.createdAt,
          sentBy = sender,
          text = em.messageText
        )
      }
    }.toMap
  }
  def getMessagesOnKeep(keepId: Id[Keep], fromIdOpt: Option[Id[ElizaMessage]], limit: Int): Future[Seq[Message]] = {
    val elizaMsgs = db.readOnlyReplica { implicit s =>
      messageThreadRepo.getByKeepId(keepId).map { thread =>
        messageRepo.getByThread(thread.id.get, fromId = fromIdOpt, limit = limit)
      }.getOrElse(Seq.empty)
    }
    externalizeMessages(elizaMsgs).map { extMessageMap =>
      elizaMsgs.flatMap(em => extMessageMap.get(em.id.get))
    }
  }

  def getDiscussionForKeep(keepId: Id[Keep]): Future[Discussion] = getDiscussionsForKeeps(Set(keepId)).imap(dm => dm(keepId))
  def getDiscussionsForKeeps(keepIds: Set[Id[Keep]]) = db.readOnlyReplica { implicit s =>
    val threadsByKeep = messageThreadRepo.getByKeepIds(keepIds)
    val threadIds = threadsByKeep.values.map(_.id.get).toSet
    val countsByThread = messageRepo.getAllMessageCounts(threadIds)
    val recentsByThread = threadIds.map { threadId =>
      threadId -> messageRepo.getByThread(threadId, fromId = None, limit = MESSAGES_TO_INCLUDE)
    }.toMap

    val extMessageMapFut = externalizeMessages(recentsByThread.values.toSeq.flatten)

    extMessageMapFut.map { extMessageMap =>
      threadsByKeep.map {
        case (kid, thread) =>
          kid -> Discussion(
            startedAt = thread.createdAt,
            numMessages = countsByThread.getOrElse(thread.id.get, 0),
            locator = thread.deepLocator,
            messages = recentsByThread(thread.id.get).flatMap(em => extMessageMap.get(em.id.get))
          )
      }
    }
  }

  // Creates a MessageThread with a keepId set (gets the uriId and title from Shoebox)
  // Does not automatically add any participants
  private def getOrCreateMessageThreadForKeep(keepId: Id[Keep]): Future[MessageThread] = {
    db.readOnlyMaster { implicit s =>
      messageThreadRepo.getByKeepId(keepId)
    }.map(Future.successful).getOrElse {
      shoebox.getCrossServiceKeepsByIds(Set(keepId)).imap { csKeeps =>
        val csKeep = csKeeps.getOrElse(keepId, throw new Exception(s"Tried to create message thread for dead keep $keepId"))
        db.readWrite { implicit s =>
          // If someone created the message thread while we were messing around in Shoebox,
          // sigh, shrug, and use that message thread. Sad waste of effort, but cést la vie
          messageThreadRepo.getByKeepId(keepId).getOrElse {
            val mt = messageThreadRepo.save(MessageThread(
              uriId = csKeep.uriId,
              url = csKeep.url,
              nUrl = csKeep.url,
              pageTitle = csKeep.title,
              startedBy = csKeep.owner,
              participants = MessageThreadParticipants(Set(csKeep.owner)),
              keepId = Some(csKeep.id)
            ))
            val ut = userThreadRepo.save(UserThread.forMessageThread(mt)(csKeep.owner))
            log.info(s"[DISC-CMDR] Created message thread ${mt.id.get} for keep $keepId, owned by ${csKeep.owner}")
            mt
          }
        }
      }
    }
  }
  def sendMessageOnKeep(userId: Id[User], txt: String, keepId: Id[Keep], source: Option[MessageSource] = None)(implicit context: HeimdalContext): Future[ElizaMessage] = {
    getOrCreateMessageThreadForKeep(keepId).imap { thread =>
      if (!thread.containsUser(userId)) {
        db.readWrite { implicit s =>
          messageThreadRepo.save(thread.withParticipants(clock.now, Set(userId)))
          val ut = userThreadRepo.save(UserThread.forMessageThread(thread)(userId))
          log.info(s"[DISC-CMDR] User $userId said $txt on keep $keepId. They're new so we added user thread ${ut.id.get} for them.")
        }
      } else { log.info(s"[DISC-CMDR] User $userId said $txt on keep $keepId, they were already part of the thread.") }
      val (_, message) = messagingCommander.sendMessage(userId, thread.id.get, txt, source, None)
      message
    }
  }

  // TODO(ryan): make this do batch processing...
  def markAsRead(userId: Id[User], keepId: Id[Keep], msgId: Id[ElizaMessage]): Option[Int] = db.readWrite { implicit s =>
    for {
      mt <- messageThreadRepo.getByKeepId(keepId)
      ut <- userThreadRepo.getUserThread(userId, mt.id.get)
    } yield {
      val unreadCount = messageRepo.countByThread(mt.id.get, Some(msgId), SortDirection.ASCENDING)

      // TODO(ryan): drop UserThread.unread and instead have a `UserThread.lastSeenMessageId` and compare to `messageRepo.getLatest(threadId)`
      // Then you can just set it and forget it
      if (unreadCount == 0) {
        userThreadRepo.markRead(userId, mt.id.get, messageRepo.get(msgId))
      }
      unreadCount
    }
  }

  def editMessage(messageId: Id[ElizaMessage], newText: String): ElizaMessage = db.readWrite { implicit s =>
    messageRepo.save(messageRepo.get(messageId).withText(newText))
  }

  def deleteMessageOnKeep(userId: Id[User], keepId: Id[Keep], messageId: Id[ElizaMessage]): Try[Unit] = db.readWrite { implicit s =>
    for {
      msg <- Some(messageRepo.get(messageId)).filter(_.isActive).map(Success(_)).getOrElse(Failure(new Exception("message does not exist")))
      thread <- Some(messageThreadRepo.get(msg.thread)).filter(_.keepId.contains(keepId)).map(Success(_)).getOrElse(Failure(new Exception("wrong keep id!")))
      // TODO(ryan): stop checking permissions in Eliza
      // Three options that I can see, in order of how much I like them:
      //  1. trust Shoebox to ask for reasonable things, only do simple sanity checks (i.e., pass in a KeepId and make sure the msg is on that keep)
      //  2. asynchronously ask for permissions from Shoebox, do all checking here
      //  3. pass in permissions (from Shoebox), double-check them here
      owner <- msg.from.asUser.filter(_ == userId).map(Success(_)).getOrElse(Failure(new Exception("wrong owner")))
    } yield messageRepo.deactivate(msg)
  }

}
