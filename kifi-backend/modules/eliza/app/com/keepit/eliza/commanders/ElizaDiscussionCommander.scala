package com.keepit.eliza.commanders

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.akka.SafeFuture
import com.keepit.common.core.{ anyExtensionOps, futureExtensionOps }
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.mail.{ EmailAddress, BasicContact }
import com.keepit.common.time._
import com.keepit.discussion.{ CrossServiceKeepActivity, MessageSource, DiscussionFail, Discussion, Message }
import com.keepit.eliza.model._
import com.keepit.heimdal.HeimdalContext
import com.keepit.model._
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.social.BasicUserLikeEntity
import org.joda.time.DateTime

import scala.concurrent.{ ExecutionContext, Future }

@ImplementedBy(classOf[ElizaDiscussionCommanderImpl])
trait ElizaDiscussionCommander {
  def getMessagesOnKeep(keepId: Id[Keep], fromIdOpt: Option[Id[ElizaMessage]], limit: Int): Future[Seq[Message]]
  def getDiscussionForKeep(keepId: Id[Keep], maxMessagesShown: Int): Future[Discussion]
  def getDiscussionsForKeeps(keepIds: Set[Id[Keep]], maxMessagesShown: Int): Future[Map[Id[Keep], Discussion]]
  def getCrossServiceKeepActivity(keepIds: Set[Id[Keep]], maxEventsPerKeep: Int): Future[Map[Id[Keep], CrossServiceKeepActivity]]
  def getEmailParticipantsForKeeps(keepIds: Set[Id[Keep]]): Map[Id[Keep], Map[EmailAddress, (Id[User], DateTime)]]
  def sendMessage(userId: Id[User], txt: String, keepId: Id[Keep], source: Option[MessageSource] = None)(implicit context: HeimdalContext): Future[Message]
  def addParticipantsToThread(adderUserId: Id[User], keepId: Id[Keep], newParticipantsExtIds: Seq[Id[User]], emailContacts: Seq[BasicContact], orgs: Seq[Id[Organization]])(implicit context: HeimdalContext): Future[Boolean]
  def muteThread(userId: Id[User], keepId: Id[Keep])(implicit context: HeimdalContext): Future[Boolean]
  def unmuteThread(userId: Id[User], keepId: Id[Keep])(implicit context: HeimdalContext): Future[Boolean]
  def markAsRead(userId: Id[User], keepId: Id[Keep], msgId: Id[ElizaMessage]): Option[Int]
  def editMessage(messageId: Id[ElizaMessage], newText: String): Future[Message]
  def deleteMessage(messageId: Id[ElizaMessage]): Unit
  def keepHasAccessToken(keepId: Id[Keep], accessToken: ThreadAccessToken): Boolean
  def editParticipantsOnKeep(keepId: Id[Keep], editor: Id[User], newUsers: Set[Id[User]], newLibraries: Set[Id[Library]]): Future[Set[Id[User]]]
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
  notifDeliveryCommander: NotificationDeliveryCommander,
  clock: Clock,
  airbrake: AirbrakeNotifier,
  shoebox: ShoeboxServiceClient,
  implicit val defaultContext: ExecutionContext,
  implicit val publicIdConfig: PublicIdConfiguration)
    extends ElizaDiscussionCommander with Logging {

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

  def getDiscussionForKeep(keepId: Id[Keep], maxMessagesShown: Int): Future[Discussion] = getDiscussionsForKeeps(Set(keepId), maxMessagesShown).imap(dm => dm(keepId))

  def getDiscussionsForKeeps(keepIds: Set[Id[Keep]], maxMessagesShown: Int) = {
    val recentsByKeep = db.readOnlyReplica { implicit s =>
      keepIds.map { keepId =>
        keepId -> messageRepo.getByKeep(keepId, fromId = None, limit = maxMessagesShown)
      }.toMap
    }

    val extMessageMapFut = externalizeMessages(recentsByKeep.values.toSeq.flatten)

    val infoF = db.readOnlyReplicaAsync { implicit s =>
      val threadsByKeep = messageThreadRepo.getByKeepIds(keepIds)
      val countsByKeep = messageRepo.getAllMessageCounts(keepIds)
      (threadsByKeep, countsByKeep)
    }

    for {
      (threadsByKeep, countsByKeep) <- infoF
      extMessageMap <- extMessageMapFut
    } yield {
      threadsByKeep.map {
        case (keepId, thread) =>
          keepId -> Discussion(
            startedAt = thread.createdAt,
            numMessages = countsByKeep.getOrElse(keepId, 0),
            locator = thread.deepLocator,
            messages = recentsByKeep(keepId).flatMap(em => extMessageMap.get(em.id.get))
          )
      }
    }
  }

  def getCrossServiceKeepActivity(keepIds: Set[Id[Keep]], maxEventsPerKeep: Int): Future[Map[Id[Keep], CrossServiceKeepActivity]] = {
    val countByKeepFut = db.readOnlyMasterAsync(implicit s => messageRepo.getAllMessageCounts(keepIds))
    val recentsByKeepFut = db.readOnlyMasterAsync { implicit s =>
      keepIds.map { keepId =>
        keepId -> messageRepo.getByKeep(keepId, fromId = None, limit = maxEventsPerKeep)
      }.toMap
    }

    for {
      countByKeep <- countByKeepFut
      recentsByKeep <- recentsByKeepFut
    } yield {
      recentsByKeep.map {
        case (keepId, messages) =>
          keepId -> CrossServiceKeepActivity(
            numComments = countByKeep.getOrElse(keepId, 0),
            messages = messages.map(ElizaMessage.toCrossServiceMessage)
          )
      }
    }
  }

  def getEmailParticipantsForKeeps(keepIds: Set[Id[Keep]]): Map[Id[Keep], Map[EmailAddress, (Id[User], DateTime)]] = {
    db.readOnlyMaster { implicit session =>
      nonUserThreadRepo.getByKeepIds(keepIds)
    }.mapValues {
      _.map(t => (t.participant, t.createdBy, t.createdAt)).collect {
        case (NonUserEmailParticipant(address), addedBy, addedAt) => address -> (addedBy, addedAt)
      }.toMap
    }
  }

  private def getOrCreateMessageThreadWithUser(keepId: Id[Keep], userId: Id[User]): Future[MessageThread] = {
    val threadFut = db.readOnlyMaster { implicit s =>
      messageThreadRepo.getByKeepId(keepId)
    }.map(Future.successful).getOrElse {
      shoebox.getCrossServiceKeepsByIds(Set(keepId)).imap { csKeeps =>
        val csKeep = csKeeps.getOrElse(keepId, throw DiscussionFail.INVALID_KEEP_ID)
        val users = csKeep.owner.toSet + userId
        db.readWrite { implicit s =>
          // If someone created the message thread while we were messing around in Shoebox,
          // sigh, shrug, and use that message thread. Sad waste of effort, but cést la vie
          messageThreadRepo.getByKeepId(keepId).getOrElse {
            val mt = messageThreadRepo.intern(MessageThread(
              uriId = csKeep.uriId,
              url = csKeep.url,
              nUrl = csKeep.url,
              pageTitle = csKeep.title,
              startedBy = csKeep.owner getOrElse userId,
              participants = MessageThreadParticipants(users),
              keepId = csKeep.id
            ))
            users.foreach(userId => userThreadRepo.intern(UserThread.forMessageThread(mt)(userId)))
            log.info(s"[DISC-CMDR] Created message thread ${mt.id.get} for keep $keepId, owned by ${csKeep.owner}")
            mt
          }
        }
      }
    }
    threadFut.map { thread =>
      if (!thread.containsUser(userId)) {
        db.readWrite { implicit s =>
          messageThreadRepo.save(thread.withParticipants(clock.now, Set(userId))) tap { updatedThread =>
            val ut = userThreadRepo.intern(UserThread.forMessageThread(updatedThread)(userId))
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
      messagingCommander.addParticipantsToThread(adderUserId, keepId, newUsers, emailContacts, orgs, newLibs = Seq.empty, updateShoebox = true)
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
      messageRepo.countByKeep(keepId, Some(msgId), SortDirection.ASCENDING).unread tap { unreadCount =>
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

    SafeFuture.wrap(tryToFixThreadNotif(editedMsg.keepId))
    externalizeMessage(editedMsg)
  }

  def deleteMessage(messageId: Id[ElizaMessage]): Unit = {
    val keepForDeletedMsg = db.readWrite { implicit s =>
      val msg = messageRepo.get(messageId)
      messageRepo.deactivate(msg)
      msg.keepId
    }

    SafeFuture.wrap(tryToFixThreadNotif(keepForDeletedMsg))
  }

  def keepHasAccessToken(keepId: Id[Keep], accessToken: ThreadAccessToken): Boolean = db.readOnlyMaster { implicit s =>
    nonUserThreadRepo.getByAccessToken(accessToken).exists(_.keepId == keepId)
  }

  def editParticipantsOnKeep(keepId: Id[Keep], editor: Id[User], newUsers: Set[Id[User]], newLibraries: Set[Id[Library]]): Future[Set[Id[User]]] = {
    implicit val context = HeimdalContext.empty
    for {
      thread <- getOrCreateMessageThreadWithUser(keepId, editor)
      _ <- messagingCommander.addParticipantsToThread(editor, keepId, newUsers.toSeq, Seq.empty, Seq.empty, newLibraries.toSeq, updateShoebox = false)
    } yield thread.allParticipants ++ newUsers
  }
  def deleteThreadsForKeeps(keepIds: Set[Id[Keep]]): Unit = db.readWrite { implicit s =>
    keepIds.foreach { keepId =>
      val uts = userThreadRepo.getByKeep(keepId)
      val nuts = nonUserThreadRepo.getByKeepId(keepId)
      val (nUrlOpt, lastMsgOpt) = (messageThreadRepo.getByKeepId(keepId).map(_.nUrl), messageRepo.getLatest(keepId))
      s.onTransactionSuccess {
        uts.foreach { ut =>
          for { nUrl <- nUrlOpt; lastMsg <- lastMsgOpt } notifDeliveryCommander.notifyRead(ut.user, ut.keepId, lastMsg.id.get, nUrl, lastMsg.createdAt)
          notifDeliveryCommander.notifyRemoveThread(ut.user, ut.keepId)
        }
      }
      uts.foreach(userThreadRepo.deactivate)
      nuts.foreach(nonUserThreadRepo.deactivate)

      messageThreadRepo.getByKeepId(keepId).foreach(messageThreadRepo.deactivate)
      messageRepo.getAllByKeep(keepId).foreach(messageRepo.deactivate)
    }
  }

  private def tryToFixThreadNotif(keepId: Id[Keep]): Future[Unit] = {
    // This gives a half-hearted attempt to fix notifications on clients for a given keep id
    // it only works if: a) there is a thread for the given keep, b) there was a message sent on that keep
    db.readOnlyMaster { implicit s =>
      for {
        thread <- messageThreadRepo.getByKeepId(keepId)
        lastMsg <- messageRepo.getLatest(keepId)
      } yield (thread, lastMsg)
    }.map {
      case (thread, lastMsg) =>
        shoebox.getBasicUsers(thread.allParticipants.toSeq).map { basicUserById =>

          val sender = lastMsg.from match {
            case MessageSender.User(id) => Some(BasicUserLikeEntity(basicUserById(id)))
            case MessageSender.NonUser(nup) => Some(BasicUserLikeEntity(NonUserParticipant.toBasicNonUser(nup)))
            case _ => None
          }

          val threadActivity: Seq[UserThreadActivity] = db.readOnlyMaster { implicit session =>
            userThreadRepo.getThreadActivity(keepId).sortBy { uta =>
              (-uta.lastActive.getOrElse(START_OF_TIME).getMillis, uta.id.id)
            }
          }

          thread.allParticipants.foreach { user =>
            notifDeliveryCommander.sendNotificationForMessage(user, lastMsg, thread, sender, threadActivity, forceOverwrite = true)
          }
        }
    }.getOrElse(Future.successful(Unit))
  }

}
