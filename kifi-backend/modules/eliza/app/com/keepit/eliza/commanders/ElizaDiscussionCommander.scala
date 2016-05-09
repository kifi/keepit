package com.keepit.eliza.commanders

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.akka.SafeFuture
import com.keepit.common.core.{ anyExtensionOps, futureExtensionOps }
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.mail.{ BasicContact, EmailAddress }
import com.keepit.common.time._
import com.keepit.discussion._
import com.keepit.eliza.model._
import com.keepit.heimdal.HeimdalContext
import com.keepit.model.KeepEventData.{ EditTitle, ModifyRecipients }
import com.keepit.model._
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.social.BasicUserLikeEntity
import org.joda.time.DateTime

import scala.concurrent.{ ExecutionContext, Future }

@ImplementedBy(classOf[ElizaDiscussionCommanderImpl])
trait ElizaDiscussionCommander {
  def internThreadForKeep(csKeep: CrossServiceKeep, owner: Id[User]): (MessageThread, Boolean)
  def getMessagesOnKeep(keepId: Id[Keep], fromIdOpt: Option[Id[ElizaMessage]], limit: Int): Future[Seq[Message]]
  def getCrossServiceDiscussionsForKeeps(keepIds: Set[Id[Keep]], fromTime: Option[DateTime], maxMessagesShown: Int): Map[Id[Keep], CrossServiceDiscussion]
  def syncAddParticipants(keepId: Id[Keep], event: KeepEventData.ModifyRecipients, source: Option[KeepEventSource]): Future[Unit]
  def getEmailParticipantsForKeeps(keepIds: Set[Id[Keep]]): Map[Id[Keep], Map[EmailAddress, (Id[User], DateTime)]]
  def sendMessage(userId: Id[User], txt: String, keepId: Id[Keep], source: Option[MessageSource])(implicit context: HeimdalContext): Future[Message]
  def editParticipantsOnKeep(keepId: Id[Keep], editor: Id[User], newUsers: Seq[Id[User]], newNonUsers: Seq[BasicContact], orgs: Seq[Id[Organization]], source: Option[KeepEventSource])(implicit context: HeimdalContext): Future[Boolean]
  def handleKeepEvent(keepId: Id[Keep], commonEvent: CommonKeepEvent, basicEvent: BasicKeepEvent, source: Option[KeepEventSource])(implicit context: HeimdalContext): Future[Unit]
  def muteThread(userId: Id[User], keepId: Id[Keep])(implicit context: HeimdalContext): Future[Boolean]
  def unmuteThread(userId: Id[User], keepId: Id[Keep])(implicit context: HeimdalContext): Future[Boolean]
  def markAsRead(userId: Id[User], keepId: Id[Keep], msgId: Id[ElizaMessage]): Option[Int]
  def editMessage(messageId: Id[ElizaMessage], newText: String): Future[Message]
  def deleteMessage(messageId: Id[ElizaMessage]): Unit
  def keepHasAccessToken(keepId: Id[Keep], accessToken: ThreadAccessToken): Boolean
  def deleteThreadsForKeeps(keepIds: Set[Id[Keep]])(implicit session: RWSession): Unit
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
          text = em.messageText,
          source = em.source
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

  def getCrossServiceDiscussionsForKeeps(keepIds: Set[Id[Keep]], fromTime: Option[DateTime], maxMessagesShown: Int): Map[Id[Keep], CrossServiceDiscussion] = {
    val recentsByKeep = db.readOnlyReplica { implicit s =>
      keepIds.map { keepId =>
        keepId -> messageRepo.getByKeepBefore(keepId, fromOpt = fromTime, limit = maxMessagesShown)
      }.toMap
    }

    val (threadsByKeep, countsByKeep) = db.readOnlyReplica { implicit s =>
      val threadsByKeep = messageThreadRepo.getByKeepIds(keepIds)
      val countsByKeep = messageRepo.getNumCommentsByKeep(keepIds)
      (threadsByKeep, countsByKeep)
    }

    threadsByKeep.map {
      case (keepId, thread) =>
        keepId -> CrossServiceDiscussion(
          startedAt = thread.createdAt,
          numMessages = countsByKeep.getOrElse(keepId, 0),
          locator = thread.deepLocator,
          messages = recentsByKeep(keepId).filterNot(_.from.isSystem).map(ElizaMessage.toCrossServiceMessage)
        )
    }
  }

  def syncAddParticipants(keepId: Id[Keep], event: KeepEventData.ModifyRecipients, source: Option[KeepEventSource]): Future[Unit] = {
    getOrCreateMessageThreadWithUser(keepId, event.addedBy).map { thread =>
      db.readWrite { implicit s =>
        messageRepo.save(ElizaMessage(
          keepId = thread.keepId,
          commentIndexOnKeep = None,
          from = MessageSender.System,
          messageText = "",
          source = source.flatMap(KeepEventSource.toMessageSource),
          auxData = SystemMessageData.fromKeepEvent(event),
          sentOnUrl = None,
          sentOnUriId = None
        ))
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
    }.map(mt => Future.successful((mt, false))).getOrElse {
      shoebox.getCrossServiceKeepsByIds(Set(keepId)).imap { csKeeps =>
        val csKeep = csKeeps.getOrElse(keepId, throw DiscussionFail.INVALID_KEEP_ID)
        internThreadForKeep(csKeep, userId)
      }
    }
    threadFut.map {
      case (oldThread, isNew) =>
        val newThread = if (oldThread.containsUser(userId)) {
          oldThread
        } else {
          db.readWrite { implicit s =>
            messageThreadRepo.save(oldThread.withParticipants(clock.now, Set(userId))) tap { updatedThread =>
              userThreadRepo.intern(UserThread.forMessageThread(updatedThread)(userId))
            }
          }
        }

        if (!oldThread.containsUser(userId) || isNew) {
          shoebox.getRecipientsOnKeep(keepId).map {
            case (basicUsers, basicLibraries, emails) =>
              notifDeliveryCommander.sendKeepRecipients(newThread.participants.allUsers, oldThread.pubKeepId, basicUsers.values.toSet, basicLibraries.values.toSet, emails)
          }
        }

        newThread
    }
  }
  def internThreadForKeep(csKeep: CrossServiceKeep, owner: Id[User]): (MessageThread, Boolean) = {
    val users = csKeep.users ++ csKeep.owner
    db.readWrite { implicit s =>
      messageThreadRepo.getByKeepId(csKeep.id).map(_ -> false).getOrElse {
        val mt = messageThreadRepo.intern(MessageThread(
          uriId = csKeep.uriId,
          url = csKeep.url,
          nUrl = csKeep.url,
          pageTitle = csKeep.title,
          startedBy = csKeep.owner getOrElse owner,
          participants = MessageThreadParticipants(users),
          keepId = csKeep.id,
          numMessages = 0
        ))
        users.foreach(userId => userThreadRepo.intern(UserThread.forMessageThread(mt)(userId)))
        csKeep.emails.foreach(email => nonUserThreadRepo.intern(NonUserThread.forMessageThread(mt)(NonUserEmailParticipant(email))))
        (mt, true)
      }
    }
  }

  def sendMessage(userId: Id[User], txt: String, keepId: Id[Keep], source: Option[MessageSource] = None)(implicit context: HeimdalContext): Future[Message] = {
    getOrCreateMessageThreadWithUser(keepId, userId).flatMap { thread =>
      val (_, message) = messagingCommander.sendMessage(userId, thread, txt, source, None)
      externalizeMessage(message)
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

  def markAsRead(userId: Id[User], keepId: Id[Keep], msgId: Id[ElizaMessage]): Option[Int] = db.readWrite { implicit s =>
    for {
      ut <- userThreadRepo.getUserThread(userId, keepId)
    } yield {
      messageRepo.countByKeep(keepId, Some(msgId), SortDirection.ASCENDING).unread tap { unreadCount =>
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

  // path for modifying participants from old clients: client --> eliza (thread updates) -> shoebox (keep event updates) -> eliza (event notifications)
  def editParticipantsOnKeep(keepId: Id[Keep], editor: Id[User], newUsers: Seq[Id[User]], newNonUsers: Seq[BasicContact], orgs: Seq[Id[Organization]], source: Option[KeepEventSource])(implicit context: HeimdalContext): Future[Boolean] = {
    implicit val context = HeimdalContext.empty
    for {
      thread <- getOrCreateMessageThreadWithUser(keepId, editor)
      threadAndDiffOpt <- messagingCommander.addParticipantsToThread(editor, keepId, newUsers, newNonUsers, orgs.toSeq, source)
      success <- threadAndDiffOpt.map {
        case (updatedThread, diff) =>
          shoebox.persistModifyRecipients(keepId, ModifyRecipients(editor, diff), source).map {
            case None => false
            case Some(CommonAndBasicKeepEvent(commonEvent, basicEvent)) =>
              notifDeliveryCommander.notifyAddParticipants(editor, diff, updatedThread, commonEvent, basicEvent, source)
              true
          }
      }.getOrElse(Future.successful(false))
    } yield success
  }

  // path for modifying participants: client -> shoebox (keep event updates) -> eliza (thread updates + event notifications)
  def handleKeepEvent(keepId: Id[Keep], commonEvent: CommonKeepEvent, basicEvent: BasicKeepEvent, source: Option[KeepEventSource])(implicit context: HeimdalContext): Future[Unit] = {
    implicit val context = HeimdalContext.empty
    commonEvent.eventData match {
      case et: EditTitle =>
        getOrCreateMessageThreadWithUser(keepId, et.editedBy).map { thread =>
          thread.participants.allUsers.foreach { uid => notifDeliveryCommander.sendKeepEvent(uid, Keep.publicId(keepId), basicEvent) }
        }
      case ModifyRecipients(editor, proposedDiff) =>
        getOrCreateMessageThreadWithUser(keepId, editor).flatMap { thread =>
          messagingCommander.addParticipantsToThread(editor, keepId, proposedDiff.users.added.toSeq, proposedDiff.emails.added.map(BasicContact(_)).toSeq, orgIds = Seq.empty, source).map {
            case None => Unit
            case Some((updatedThread, realDiff)) =>
              notifDeliveryCommander.notifyAddParticipants(editor, realDiff, updatedThread, commonEvent, basicEvent, source)
          }
        }
    }
  }

  def deleteThreadsForKeeps(keepIds: Set[Id[Keep]])(implicit session: RWSession): Unit = {
    keepIds.foreach { keepId =>
      val uts = userThreadRepo.getByKeep(keepId)
      val nuts = nonUserThreadRepo.getByKeepId(keepId)
      val (nUrlOpt, lastMsgOpt) = (messageThreadRepo.getByKeepId(keepId).map(_.nUrl), messageRepo.getLatest(keepId))
      session.onTransactionSuccess {
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
