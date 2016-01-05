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

import scala.collection.parallel.immutable.ParSeq
import scala.concurrent.{ ExecutionContext, Future }

@ImplementedBy(classOf[ElizaDiscussionCommanderImpl])
trait ElizaDiscussionCommander {
  def getMessagesOnKeep(keepId: Id[Keep], fromIdOpt: Option[Id[ElizaMessage]], limit: Int): Future[Seq[Message]]
  def getDiscussionForKeep(keepId: Id[Keep]): Future[Discussion]
  def getDiscussionsForKeeps(keepIds: Set[Id[Keep]]): Future[Map[Id[Keep], Discussion]]
  def sendMessage(userId: Id[User], txt: String, keepId: Id[Keep], source: Option[MessageSource] = None)(implicit context: HeimdalContext): Future[Message]
  def addParticipantsToThread(adderUserId: Id[User], keepId: Id[Keep], newParticipantsExtIds: Seq[Id[User]], emailContacts: Seq[BasicContact], orgs: Seq[Id[Organization]])(implicit context: HeimdalContext): Future[Boolean]
  def muteThread(userId: Id[User], keepId: Id[Keep])(implicit context: HeimdalContext): Future[Boolean]
  def unmuteThread(userId: Id[User], keepId: Id[Keep])(implicit context: HeimdalContext): Future[Boolean]
  def markAsRead(userId: Id[User], keepId: Id[Keep], msgId: Id[ElizaMessage]): Option[Int]
  def editMessage(messageId: Id[ElizaMessage], newText: String): Future[Message]
  def deleteMessage(messageId: Id[ElizaMessage]): Unit
  def keepHasAccessToken(keepId: Id[Keep], accessToken: ThreadAccessToken): Boolean
  def editParticipantsOnKeep(keepId: Id[Keep], editor: Id[User], newUsers: Set[Id[User]]): Future[Set[Id[User]]]
  def deleteThreadsForKeeps(keepIds: Set[Id[Keep]]): Unit
}

@Singleton
class ElizaDiscussionCommanderImpl @Inject() (
  db: Database,
  messageThreadRepo: MessageThreadRepo,
  userThreadRepo: UserThreadRepo,
  nonUserThreadRepo: NonUserThreadRepo,
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
      messageRepo.getByKeep(keepId, fromId = fromIdOpt, limit = limit)
    }
    externalizeMessages(elizaMsgs).map { extMessageMap =>
      elizaMsgs.flatMap(em => extMessageMap.get(em.id.get))
    }
  }

  def getDiscussionForKeep(keepId: Id[Keep]): Future[Discussion] = getDiscussionsForKeeps(Set(keepId)).imap(dm => dm(keepId))
  def getDiscussionsForKeeps(keepIds: Set[Id[Keep]]) = db.readOnlyReplica { implicit s =>
    val threadsByKeep = messageThreadRepo.getByKeepIds(keepIds)
    val countsByKeep = messageRepo.getAllMessageCounts(keepIds)
    val recentsByKeep = keepIds.map { keepId =>
      keepId -> messageRepo.getByKeep(keepId, fromId = None, limit = MESSAGES_TO_INCLUDE)
    }.toMap

    val extMessageMapFut = externalizeMessages(recentsByKeep.values.toSeq.flatten)

    extMessageMapFut.map { extMessageMap =>
      threadsByKeep.map {
        case (kid, thread) =>
          kid -> Discussion(
            startedAt = thread.createdAt,
            numMessages = countsByKeep.getOrElse(kid, 0),
            locator = thread.deepLocator,
            messages = recentsByKeep(kid).flatMap(em => extMessageMap.get(em.id.get))
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

  private def getOrCreateMessageThreadWithUser(keepId: Id[Keep], userId: Id[User]): Future[MessageThread] = {
    val futureMessageThread = getOrCreateMessageThreadForKeep(keepId)
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

  def sendMessage(userId: Id[User], txt: String, keepId: Id[Keep], source: Option[MessageSource] = None)(implicit context: HeimdalContext): Future[Message] = {
    getOrCreateMessageThreadWithUser(keepId, userId).flatMap { thread =>
      val (_, message) = messagingCommander.sendMessage(userId, thread, txt, source, None)
      externalizeMessage(message)
    }
  }

  def addParticipantsToThread(adderUserId: Id[User], keepId: Id[Keep], newUsers: Seq[Id[User]], emailContacts: Seq[BasicContact], orgs: Seq[Id[Organization]])(implicit context: HeimdalContext): Future[Boolean] = {
    getOrCreateMessageThreadWithUser(keepId, adderUserId).flatMap { thread =>
      messagingCommander.addParticipantsToThread(adderUserId, keepId, newUsers, emailContacts, orgs)
    }
  }

  def muteThread(userId: Id[User], keepId: Id[Keep])(implicit context: HeimdalContext): Future[Boolean] = {
    getOrCreateMessageThreadWithUser(keepId, userId).map { _ =>
      messagingCommander.setUserThreadMuteState(userId, keepId, mute = true)
    }
  }

  def unmuteThread(userId: Id[User], keepId: Id[Keep])(implicit context: HeimdalContext): Future[Boolean] = {
    getOrCreateMessageThreadWithUser(keepId, userId).map { _ =>
      messagingCommander.setUserThreadMuteState(userId, keepId, mute = false)
    }
  }

  // TODO(ryan): make this do batch processing...
  def markAsRead(userId: Id[User], keepId: Id[Keep], msgId: Id[ElizaMessage]): Option[Int] = db.readWrite { implicit s =>
    for {
      ut <- userThreadRepo.getUserThread(userId, keepId)
    } yield {
      messageRepo.countByKeep(keepId, Some(msgId), SortDirection.ASCENDING) tap { unreadCount =>
        // TODO(ryan): drop UserThread.unread and instead have a `UserThread.lastSeenMessageId` and compare to `messageRepo.getLatest(threadId)`
        // Then you can just set it and forget it
        if (unreadCount == 0) userThreadRepo.markRead(userId, messageRepo.get(msgId))
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

  def keepHasAccessToken(keepId: Id[Keep], accessToken: ThreadAccessToken): Boolean = db.readOnlyMaster { implicit s =>
    nonUserThreadRepo.getByAccessToken(accessToken).exists(_.keepId == keepId)
  }

  def editParticipantsOnKeep(keepId: Id[Keep], editor: Id[User], newUsers: Set[Id[User]]): Future[Set[Id[User]]] = {
    implicit val context = HeimdalContext.empty
    for {
      thread <- getOrCreateMessageThreadWithUser(keepId, editor)
      _ <- messagingCommander.addParticipantsToThread(editor, keepId, newUsers.toSeq, Seq.empty, Seq.empty)
    } yield thread.allParticipants ++ newUsers
  }
  def deleteThreadsForKeeps(keepIds: Set[Id[Keep]]): Unit = db.readWrite { implicit s =>
    keepIds.foreach { keepId =>
      messageThreadRepo.getByKeepId(keepId).foreach(messageThreadRepo.deactivate)
      messageRepo.getAllByKeep(keepId).foreach(messageRepo.deactivate)
      userThreadRepo.getByKeep(keepId).foreach(userThreadRepo.deactivate)
    }
  }
}
