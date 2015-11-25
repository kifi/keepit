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

@ImplementedBy(classOf[ElizaDiscussionCommanderImpl])
trait ElizaDiscussionCommander {
  def getMessagesOnKeep(keepId: Id[Keep], fromIdOpt: Option[Id[ElizaMessage]], limit: Int): Future[Seq[Message]]
  def getDiscussionsForKeeps(keepIds: Set[Id[Keep]]): Future[Map[Id[Keep], Discussion]]
  def sendMessageOnKeep(userId: Id[User], txt: String, keepId: Id[Keep], source: Option[MessageSource] = None)(implicit context: HeimdalContext): Future[ElizaMessage]
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
    } yield emsgs.map { em =>
      val msgId = Message.publicId(ElizaMessage.toMessageId(em.id.get)) // this is a hack to expose Id[ElizaMessage] without exposing ElizaMessage itself
      em.id.get -> Message(
        pubId = msgId,
        sentAt = em.createdAt,
        sentBy = em.from.asUser.map(uid => BasicUserLikeEntity(basicUsers(uid))).getOrElse {
          BasicUserLikeEntity(NonUserParticipant.toBasicNonUser(em.from.asNonUser.get))
        },
        text = em.messageText
      )
    }.toMap
  }
  def getMessagesOnKeep(keepId: Id[Keep], fromIdOpt: Option[Id[ElizaMessage]], limit: Int): Future[Seq[Message]] = {
    val elizaMsgs = db.readOnlyReplica { implicit s =>
      messageThreadRepo.getByKeepId(keepId).map { thread =>
        messageRepo.getByThread(thread.id.get, fromIdOpt, limit)
      }.getOrElse(Seq.empty)
    }
    externalizeMessages(elizaMsgs).map { extMessageMap =>
      elizaMsgs.flatMap(em => extMessageMap.get(em.id.get))
    }
  }
  def getDiscussionsForKeeps(keepIds: Set[Id[Keep]]) = db.readOnlyReplica { implicit s =>
    val threadsByKeep = messageThreadRepo.getByKeepIds(keepIds)
    val threadIds = threadsByKeep.values.map(_.id.get).toSet
    val countsByThread = messageRepo.getAllMessageCounts(threadIds)
    val recentsByThread = threadIds.map { threadId =>
      threadId -> messageRepo.getByThread(threadId, None, MESSAGES_TO_INCLUDE)
    }.toMap

    val extMessageMapFut = externalizeMessages(recentsByThread.values.toSeq.flatten)

    extMessageMapFut.map { extMessageMap =>
      threadsByKeep.map {
        case (kid, thread) =>
          kid -> Discussion(
            startedAt = thread.createdAt,
            numMessages = countsByThread.getOrElse(thread.id.get, 0),
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
            messageThreadRepo.save(MessageThread(
              uriId = Some(csKeep.uriId),
              url = Some(csKeep.url),
              nUrl = None,
              pageTitle = csKeep.title,
              participants = Some(MessageThreadParticipants.empty),
              participantsHash = None,
              keepId = Some(csKeep.id)
            ))
          }
        }
      }
    }
  }
  def sendMessageOnKeep(userId: Id[User], txt: String, keepId: Id[Keep], source: Option[MessageSource] = None)(implicit context: HeimdalContext): Future[ElizaMessage] = {
    getOrCreateMessageThreadForKeep(keepId).imap { thread =>
      if (!thread.containsUser(userId)) {
        db.readWrite { implicit s =>
          messageThreadRepo.save(thread.withParticipants(clock.now, Seq(userId)))
          userThreadRepo.save(UserThread(
            user = userId,
            threadId = thread.id.get,
            uriId = thread.uriId,
            lastSeen = None,
            unread = true,
            lastMsgFromOther = None,
            lastNotification = JsNull
          ))
        }
      }
      val (_, message) = messagingCommander.sendMessage(userId, thread.id.get, txt, source, None)
      message
    }
  }
}
