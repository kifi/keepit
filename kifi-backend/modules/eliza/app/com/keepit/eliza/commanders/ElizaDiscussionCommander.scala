package com.keepit.eliza.commanders

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.core.{ futureExtensionOps, anyExtensionOps }
import com.keepit.common.db.slick.Database
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.mail.BasicContact
import com.keepit.common.time._
import com.keepit.discussion.{ Discussion, Message }
import com.keepit.eliza.model._
import com.keepit.heimdal.HeimdalContext
import com.keepit.model._
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.social.BasicUserLikeEntity
import play.api.libs.json.JsNull
import com.keepit.common.core._

import scala.concurrent.{ ExecutionContext, Future }

@ImplementedBy(classOf[ElizaDiscussionCommanderImpl])
trait ElizaDiscussionCommander {
  def getMessagesOnKeep(keepId: Id[Keep], fromIdOpt: Option[Id[ElizaMessage]], limit: Int): Future[Seq[Message]]
  def getDiscussionForKeep(keepId: Id[Keep]): Future[Discussion]
  def getDiscussionsForKeeps(keepIds: Set[Id[Keep]]): Future[Map[Id[Keep], Discussion]]
  def sendMessage(userId: Id[User], txt: String, extThreadId: MessageThreadId, source: Option[MessageSource] = None)(implicit context: HeimdalContext): Future[Message]
  def addParticipantsToThread(adderUserId: Id[User], extThreadId: MessageThreadId, newParticipantsExtIds: Seq[ExternalId[User]], emailContacts: Seq[BasicContact], orgs: Seq[PublicId[Organization]])(implicit context: HeimdalContext): Future[Boolean]
  def muteThread(userId: Id[User], extThreadId: MessageThreadId)(implicit context: HeimdalContext): Future[Boolean]
  def unmuteThread(userId: Id[User], extThreadId: MessageThreadId)(implicit context: HeimdalContext): Future[Boolean]
  def markAsRead(userId: Id[User], keepId: Id[Keep], msgId: Id[ElizaMessage]): Option[Int]
  def editMessage(messageId: Id[ElizaMessage], newText: String): Future[Message]
  def deleteMessage(messageId: Id[ElizaMessage]): Unit
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

  private def externalizeMessage(msg: ElizaMessage): Future[Message] = {
    externalizeMessages(Seq(msg)).map(_.values.head)
  }
  private def externalizeMessages(emsgs: Seq[ElizaMessage]): Future[Map[Id[ElizaMessage], Message]] = {
    val users = emsgs.flatMap(_.from.asUser)
    val nonUsers = emsgs.flatMap(_.from.asNonUser)
    val basicUsersFut = shoebox.getBasicUsers(users)
    val basicNonUsersFut = Future.successful(nonUsers.map(nu => nu -> NonUserParticipant.toBasicNonUser(nu)).toMap)
    for {
      basicUsers <- basicUsersFut
      basicNonUsers <- basicNonUsersFut
    } yield emsgs.flatMap { em =>
      val senderOpt: Option[BasicUserLikeEntity] = em.from.fold(
        None,
        { uid => Some(BasicUserLikeEntity(basicUsers(uid))) },
        { nu => Some(BasicUserLikeEntity(basicNonUsers(nu))) }
      )
      senderOpt.map { sender =>
        em.id.get -> Message(
          pubId = em.pubId,
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
              keepId = csKeep.id
            ))
            val ut = userThreadRepo.save(UserThread.forMessageThread(mt)(csKeep.owner))
            log.info(s"[DISC-CMDR] Created message thread ${mt.id.get} for keep $keepId, owned by ${csKeep.owner}")
            mt
          }
        }
      }
    }
  }

  private def getOrCreateMessageThreadWithUser(extThreadId: MessageThreadId, userId: Id[User]): Future[MessageThread] = {
    val futureMessageThread = extThreadId match {
      case ThreadExternalId(threadId) => db.readOnlyMaster { implicit session => Future.successful(messageThreadRepo.get(threadId)) }
      case KeepId(keepId) => getOrCreateMessageThreadForKeep(keepId)
    }
    futureMessageThread.map { thread =>
      if (!thread.containsUser(userId)) {
        db.readWrite { implicit s =>
          messageThreadRepo.save(thread.withParticipants(clock.now, Set(userId))) tap { updatedThread =>
            val ut = userThreadRepo.save(UserThread.forMessageThread(updatedThread)(userId))
            log.info(s"[DISC-CMDR] User $userId was added to thread ${thread.id.get} associated with keep ${thread.keepId}.")
          }
        }
      } else {
        log.info(s"[DISC-CMDR] User $userId is already part of thread ${thread.id.get} associated with keep ${thread.keepId}.")
        thread
      }
    }
  }

  def sendMessage(userId: Id[User], txt: String, extThreadId: MessageThreadId, source: Option[MessageSource] = None)(implicit context: HeimdalContext): Future[Message] = {
    getOrCreateMessageThreadWithUser(extThreadId, userId).flatMap { thread =>
      val (_, message) = messagingCommander.sendMessage(userId, extThreadId, thread, txt, source, None)
      externalizeMessage(message)
    }
  }

  def addParticipantsToThread(adderUserId: Id[User], extThreadId: MessageThreadId, newParticipantsExtIds: Seq[ExternalId[User]], emailContacts: Seq[BasicContact], orgs: Seq[PublicId[Organization]])(implicit context: HeimdalContext): Future[Boolean] = {
    getOrCreateMessageThreadWithUser(extThreadId, adderUserId).flatMap { thread =>
      messagingCommander.addParticipantsToThread(adderUserId, extThreadId, thread.id.get, newParticipantsExtIds, emailContacts, orgs)
    }
  }

  def muteThread(userId: Id[User], extThreadId: MessageThreadId)(implicit context: HeimdalContext): Future[Boolean] = {
    getOrCreateMessageThreadWithUser(extThreadId, userId).map { _ =>
      messagingCommander.setUserThreadMuteState(userId, extThreadId, mute = true)
    }
  }

  def unmuteThread(userId: Id[User], extThreadId: MessageThreadId)(implicit context: HeimdalContext): Future[Boolean] = {
    getOrCreateMessageThreadWithUser(extThreadId, userId).map { _ =>
      messagingCommander.setUserThreadMuteState(userId, extThreadId, mute = false)
    }
  }

  // TODO(ryan): make this do batch processing...
  def markAsRead(userId: Id[User], keepId: Id[Keep], msgId: Id[ElizaMessage]): Option[Int] = db.readWrite { implicit s =>
    for {
      mt <- messageThreadRepo.getByKeepId(keepId)
      ut <- userThreadRepo.getUserThread(userId, mt.id.get)
    } yield {
      messageRepo.countByThread(mt.id.get, Some(msgId), SortDirection.ASCENDING) tap { unreadCount =>
        // TODO(ryan): drop UserThread.unread and instead have a `UserThread.lastSeenMessageId` and compare to `messageRepo.getLatest(threadId)`
        // Then you can just set it and forget it
        if (unreadCount == 0) userThreadRepo.markRead(userId, mt.id.get, messageRepo.get(msgId))
      }
    }
  }

  def editMessage(messageId: Id[ElizaMessage], newText: String): Future[Message] = {
    val editedMsg = db.readWrite { implicit s =>
      messageRepo.save(messageRepo.get(messageId).withText(newText))
    }
    externalizeMessage(editedMsg)
  }

  def deleteMessage(messageId: Id[ElizaMessage]): Unit = db.readWrite { implicit s =>
    messageRepo.deactivate(messageRepo.get(messageId))
  }
}
