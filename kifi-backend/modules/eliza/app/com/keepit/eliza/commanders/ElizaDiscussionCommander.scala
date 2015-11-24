package com.keepit.eliza.commanders

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.discussion.{ Discussion, Message }
import com.keepit.eliza.model.{ NonUserParticipant, MessageRepo, MessageThreadRepo, ElizaMessage }
import com.keepit.model._
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.social.{ BasicNonUser, BasicUserLikeEntity }

import scala.concurrent.{ ExecutionContext, Future }

@ImplementedBy(classOf[ElizaDiscussionCommanderImpl])
trait ElizaDiscussionCommander {
  def getDiscussionsForKeeps(keepIds: Set[Id[Keep]]): Future[Map[Id[Keep], Discussion]]
}

@Singleton
class ElizaDiscussionCommanderImpl @Inject() (
  db: Database,
  messageThreadRepo: MessageThreadRepo,
  messageRepo: MessageRepo,
  clock: Clock,
  airbrake: AirbrakeNotifier,
  shoebox: ShoeboxServiceClient,
  implicit val defaultContext: ExecutionContext)
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
      val msgId = ExternalId[Message](em.externalId.id) // this is a hack to expose Id[ElizaMessage] in a semi-typesafe way
      em.id.get -> Message(
        id = msgId,
        sentAt = em.createdAt,
        sentBy = em.from.asUser.map(uid => BasicUserLikeEntity(basicUsers(uid))).getOrElse {
          BasicUserLikeEntity(NonUserParticipant.toBasicNonUser(em.from.asNonUser.get))
        },
        text = em.messageText
      )
    }.toMap
  }
  def getDiscussionsForKeeps(keepIds: Set[Id[Keep]]) = db.readOnlyReplica { implicit s =>
    val threadsByKeep = messageThreadRepo.getByKeepIds(keepIds)
    val threadIds = threadsByKeep.values.map(_.id.get).toSet
    val countsByThread = messageRepo.getAllMessageCounts(threadIds)
    val recentsByThread = threadIds.map { threadId =>
      threadId -> messageRepo.getRecentByThread(threadId, None, MESSAGES_TO_INCLUDE)
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
}
