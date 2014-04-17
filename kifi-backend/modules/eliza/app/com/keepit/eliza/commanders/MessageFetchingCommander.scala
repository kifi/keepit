package com.keepit.eliza.commanders

import com.google.inject.Inject
import com.keepit.eliza.model._
import com.keepit.common.logging.Logging
import scala.concurrent.{Promise, Future}
import com.keepit.common.akka.SafeFuture
import com.keepit.common.db.{Id, ExternalId}
import com.keepit.common.db.slick.Database
import scala.Some
import com.keepit.shoebox.ShoeboxServiceClient
import play.api.libs.json.{Json, JsArray, JsString}
import com.keepit.model.User
import com.keepit.social.{BasicUserLikeEntity, BasicUser}
import org.joda.time.DateTime
import play.api.libs.concurrent.Execution.Implicits.defaultContext

class MessageFetchingCommander @Inject() (
  db: Database,
  messageRepo: MessageRepo,
  threadRepo: MessageThreadRepo,
  shoebox: ShoeboxServiceClient) extends Logging {

  def getMessageWithBasicUser(
    id: ExternalId[Message],
    createdAt: DateTime,
    text: String,
    auxData: Option[JsArray],
    url: String,
    nUrl: String,
    user: Option[BasicUser],
    participants: Seq[BasicUserLikeEntity]
  ): Future[MessageWithBasicUser] = {
    modifyMessageWithAuxData(MessageWithBasicUser(id, createdAt, text, auxData, url, nUrl, user, participants))
  }

  def getThreadMessages(thread: MessageThread, pageOpt: Option[Int]) : Seq[Message] = {
    db.readOnly {
      implicit session =>
        log.info(s"[get_thread] trying to get thread messages for thread extId ${thread.externalId}. pageOpt is $pageOpt")
        pageOpt.map {
          page =>
            val lower = MessagingCommander.THREAD_PAGE_SIZE * page
            val upper = MessagingCommander.THREAD_PAGE_SIZE * (page + 1) - 1
            log.info(s"[get_thread] getting thread messages for thread extId ${thread.externalId}. lu: $lower, $upper")
            messageRepo.get(thread.id.get, lower, Some(upper))
        } getOrElse {
          log.info(s"[get_thread] getting thread messages for thread extId ${thread.externalId}. no l/u")
          messageRepo.get(thread.id.get, 0, None)
        }
    }
  }

  def getThreadMessagesWithBasicUser(thread: MessageThread, pageOpt: Option[Int]): Future[(MessageThread, Seq[MessageWithBasicUser])] = {
    val userParticipantSet = if (thread.replyable) thread.participants.map(_.allUsers).getOrElse(Set()) else Set()
    log.info(s"[get_thread] got participants for extId ${thread.externalId}: $userParticipantSet")
    val messagesFut: Future[Seq[MessageWithBasicUser]] = new SafeFuture(shoebox.getBasicUsers(userParticipantSet.toSeq) map { id2BasicUser =>
      val messages = getThreadMessages(thread, pageOpt)
      log.info(s"[get_thread] got raw messages for extId ${thread.externalId}: ${messages.length}")
      messages.map { message =>
        val nonUsers = thread.participants.map(_.allNonUsers.map(NonUserParticipant.toBasicNonUser)).getOrElse(Set.empty)
        MessageWithBasicUser(
          id           = message.externalId,
          createdAt    = message.createdAt,
          text         = message.messageText,
          auxData      = message.auxData,
          url          = message.sentOnUrl.getOrElse(""),
          nUrl         = thread.nUrl.getOrElse(""), //TODO Stephen: This needs to change when we have detached threads
          user         = message.from match {
            case MessageSender.User(id) => Some(id2BasicUser(id))
            case MessageSender.NonUser(nup) => Some(NonUserParticipant.toBasicNonUser(nup))
            case _ => None
          },
          participants = userParticipantSet.toSeq.map(id2BasicUser(_)) ++ nonUsers
        )
      }
    })
    messagesFut flatMap { messages =>
      Future.sequence(messages map { message => modifyMessageWithAuxData(message) })
    } map {(thread, _)}
  }

  def getThreadMessagesWithBasicUser(threadExtId: ExternalId[MessageThread], pageOpt: Option[Int]): Future[(MessageThread, Seq[MessageWithBasicUser])] = {
    val thread = db.readOnly(threadRepo.get(threadExtId)(_))
    getThreadMessagesWithBasicUser(thread, pageOpt)
  }

  private def modifyMessageWithAuxData(m: MessageWithBasicUser): Future[MessageWithBasicUser] = {
    if (m.user.isEmpty) {
      val modifiedMessage = m.auxData match {
        case Some(auxData) =>
          val auxModifiedFuture = auxData.value match {
            case JsString("add_participants") +: JsString(jsAdderUserId) +: JsArray(jsAddedUsers) +: _ =>
              val addedUsers = jsAddedUsers.map(id => Id[User](id.as[Long]))
              val adderUserId = Id[User](jsAdderUserId.toLong)
              new SafeFuture(shoebox.getBasicUsers(adderUserId +: addedUsers) map { basicUsers =>
                val adderUser = basicUsers(adderUserId)
                val addedBasicUsers = addedUsers.map(u => basicUsers(u))
                val addedUsersString = addedBasicUsers.map(s => s"${s.firstName} ${s.lastName}") match {
                  case first :: Nil => first
                  case first :: second :: Nil => first + " and " + second
                  case many => many.take(many.length - 1).mkString(", ") + ", and " + many.last
                }

                val friendlyMessage = s"${adderUser.firstName} ${adderUser.lastName} added $addedUsersString to the conversation."
                (friendlyMessage, Json.arr("add_participants", basicUsers(adderUserId), addedBasicUsers))
              })
            case s =>
              Promise.successful(("", Json.arr())).future
          }
          auxModifiedFuture.map { case (text, aux) =>
            m.copy(auxData = Some(aux), text = text, user = Some(BasicUser(ExternalId[User]("42424242-4242-4242-4242-000000000001"), "Kifi", "", "0.jpg")))
          }
        case None =>
          Promise.successful(m).future
      }
      modifiedMessage
    } else {
      Promise.successful(m).future
    }
  }
}
