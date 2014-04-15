package com.keepit.eliza.commanders

import com.google.inject.Inject

import com.keepit.abook.ABookServiceClient
import com.keepit.eliza.model._
import com.keepit.common.akka.{SafeFuture, TimeoutFuture}
import com.keepit.common.db.{Id, ExternalId}
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.mail.GenericEmailAddress
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.time._
import com.keepit.heimdal.HeimdalContext
import com.keepit.model._
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.social.{BasicUserLikeEntity, BasicUser}

import org.joda.time.DateTime

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json._
import play.modules.statsd.api.Statsd

import scala.Some
import scala.concurrent.{Promise, Await, Future}
import scala.concurrent.duration._
import java.util.concurrent.TimeoutException

case class NotAuthorizedException(msg: String) extends java.lang.Throwable(msg)

object MessagingCommander {
  val THREAD_PAGE_SIZE = 20
}


class MessagingCommander @Inject() (
  threadRepo: MessageThreadRepo,
  userThreadRepo: UserThreadRepo,
  messageRepo: MessageRepo,
  db: Database,
  clock: Clock,
  abookServiceClient: ABookServiceClient,
  messagingAnalytics: MessagingAnalytics,
  shoebox: ShoeboxServiceClient,
  airbrake: AirbrakeNotifier,
  notificationCommander: NotificationCommander,
  notificationUpdater: NotificationUpdater) extends Logging {

  private def buildThreadInfos(userId: Id[User], threads: Seq[MessageThread], requestUrl: Option[String]) : Seq[ElizaThreadInfo]  = {
    //get all involved users
    val allInvolvedUsers : Seq[Id[User]]= threads.flatMap{_.participants.map(_.allUsers).getOrElse(Set())}
    //get all basic users
    val userId2BasicUser : Map[Id[User], BasicUser] = Await.result(shoebox.getBasicUsers(allInvolvedUsers.toSeq), 2 seconds) //Temporary
    //get all messages
    val messagesByThread : Map[Id[MessageThread], Seq[Message]] = threads.map{ thread =>
      (thread.id.get, getThreadMessages(thread, None))
    }.toMap
    //get user_threads
    val userThreads : Map[Id[MessageThread], UserThread] = db.readOnly{ implicit session => threads.map{ thread =>
      (thread.id.get, userThreadRepo.getUserThread(userId, thread.id.get))
    }}.toMap

    threads.map{ thread =>

      val lastMessageOpt = messagesByThread(thread.id.get).collectFirst { case m if m.from.isDefined => m }
      if (lastMessageOpt.isEmpty) log.error(s"EMPTY THREAD! thread_id: ${thread.id.get} request_url: $requestUrl user: $userId")
      val lastMessage = lastMessageOpt.get

      val messageTimes = messagesByThread(thread.id.get).take(10).map{ message =>
        (message.externalId, message.createdAt)
      }.toMap

      val nonUsers = thread.participants.map(_.allNonUsers).getOrElse(Set()).map(NonUserParticipant.toBasicNonUser)

      ElizaThreadInfo(
        externalId = thread.externalId,
        participants = thread.participants.map(_.allUsers).getOrElse(Set()).map(userId2BasicUser(_)).toSeq ++ nonUsers.toSeq,
        digest = lastMessage.messageText,
        lastAuthor = userId2BasicUser(lastMessage.from.get).externalId,
        messageCount = messagesByThread(thread.id.get).length,
        messageTimes = messageTimes,
        createdAt = thread.createdAt,
        lastCommentedAt = lastMessage.createdAt,
        lastMessageRead = userThreads(thread.id.get).lastSeen,
        nUrl = thread.nUrl,
        url = requestUrl,
        muted = userThreads(thread.id.get).muted)
    }
  }

  def getThreadInfo(userId: Id[User], threadExtId: ExternalId[MessageThread]): ElizaThreadInfo = {
    val thread = db.readOnly { implicit session =>
      threadRepo.get(threadExtId)
    }
    buildThreadInfos(userId, Seq(thread), None).head
  }

  def getThreadInfos(userId: Id[User], url: String): Future[(String, Seq[ElizaThreadInfo])] = {
    new SafeFuture(shoebox.getNormalizedUriByUrlOrPrenormalize(url).map {
      case Left(nUri) =>
        val threads = db.readOnly { implicit session =>
          val threadIds = userThreadRepo.getThreadIds(userId, nUri.id)
          threadIds.map(threadRepo.get)
        }.filter(_.replyable)
        val unsortedInfos = buildThreadInfos(userId, threads, Some(url))
        val infos = unsortedInfos sortWith { (a,b) =>
          a.lastCommentedAt.compareTo(b.lastCommentedAt) < 0
        }
        (nUri.url, infos)
      case Right(prenormalizedUrl) => (prenormalizedUrl, Seq[ElizaThreadInfo]())
    })
  }

  def hasThreads(userId: Id[User], url: String): Future[Boolean] = {
    shoebox.getNormalizedURIByURL(url).map {
      case Some(nUri) => db.readOnly { implicit session => userThreadRepo.hasThreads(userId, nUri.id.get) }
      case None => false
    }
  }

  def deleteUserThreadsForMessageId(id: Id[Message]): Unit = {
    val (threadExtId, userThreads) : (ExternalId[MessageThread],Seq[UserThread])= db.readOnly { implicit session =>
      val message = messageRepo.get(id)
      val threadId = message.thread
      (message.threadExtId, userThreadRepo.getByThread(threadId))
    }
    if (userThreads.length != 1) {
      airbrake.notify(s"Trying to delete notification for thread $threadExtId with not exactly one participant. Not permitted.")
    } else {
      userThreads.foreach{ userThread =>
        db.readWrite { implicit session =>
          userThreadRepo.delete(userThread)
        }
        notificationCommander.sendToUser(userThread.user, Json.arr("remove_thread", threadExtId.id))
      }
    }
  }

  val engineers = Seq(
    "ae5d159c-5935-4ad5-b979-ea280cb6c7ba", // eishay
    "dc6cb121-2a69-47c7-898b-bc2b9356054c", // andrew
    "772a9c0f-d083-44cb-87ce-de564cbbfa22", // yasu
    "d3cdb758-27df-4683-a589-e3f3d99fa47b", // jared (of the jacobs variety)
    "6d8e337d-4199-49e1-a95c-e4aab582eeca", // yinjgie
    "b80511f6-8248-4799-a17d-f86c1508c90d", // léo
    "597e6c13-5093-4cba-8acc-93318987d8ee", // stephen
    "147c5562-98b1-4fc1-946b-3873ac4a45b4", // eduardo
    "70927814-6a71-4eb4-85d4-a60164bae96c", // ray
    "1714ac86-4ce5-4083-b4c7-bb1e8292c373", // martin
    "fd187ca1-2921-4c60-a8c0-955065d454ab"  // jared (of the petker variety)
  )
  val product = Seq (
    "3ad31932-f3f9-4fe3-855c-3359051212e5", // danny
    "2d18cd0b-ef30-4759-b6c5-f5f113a30f08", // effi
    "73b1134d-02d4-443f-b99b-e8bc571455e2", // chandler
    "c82b0fa0-6438-4892-8738-7fa2d96f1365", // ketan
    "ae139ae4-49ad-4026-b215-1ece236f1322", // jen
    "c1ce2ab6-8211-40f7-8187-1522086f0c2e"  // mark
  )
  val family = engineers ++ product ++ Seq(
    "e890b13a-e33c-4110-bd11-ddd51ec4eceb" // two-meals
  )

  private def constructUserRecipients(userExtIds: Seq[ExternalId[User]]): Future[Seq[Id[User]]] = {
    val loadedUser = userExtIds.filter(_.id != "00000000-0000-0000-0000-000000000000") map {
      case ExternalId("42424242-4242-4242-4242-424242424201") => // FortyTwo Engineering
        engineers.map(ExternalId[User])
      case ExternalId("42424242-4242-4242-4242-424242424202") => // FortyTwo Family
        family.map(ExternalId[User])
      case ExternalId("42424242-4242-4242-4242-424242424203") => // FortyTwo Product
        product.map(ExternalId[User])
      case notAGroup => Seq(notAGroup)
    }
    shoebox.getUserIdsByExternalIds(loadedUser.flatten)
  }

  private def constructNonUserRecipients(userId: Id[User], nonUsers: Seq[NonUserParticipant]): Future[Seq[NonUserParticipant]] = {
    val pimpedParticipants = nonUsers.map {
      case email: NonUserEmailParticipant =>
        abookServiceClient.getEContactByEmail(userId, email.address.address).map {
          case Some(eContact) => email.copy(econtactId = eContact.id)
          case None => email // todo: Wire it up to create contact!
        }
    }
    Future.sequence(pimpedParticipants)
  }

  def sendNewMessage(from: Id[User], userRecipients: Seq[Id[User]], nonUserRecipients: Seq[NonUserParticipant], urls: JsObject, titleOpt: Option[String], messageText: String)(implicit context: HeimdalContext) : (MessageThread, Message) = {
    val userParticipants = (from +: userRecipients).distinct
    val urlOpt = (urls \ "url").asOpt[String]
    val tStart = currentDateTime
    val nUriOpt = urlOpt.map { url: String => Await.result(shoebox.internNormalizedURI(url, scrapeWanted = true), 10 seconds)} // todo: Remove Await
    Statsd.timing(s"messaging.internNormalizedURI", currentDateTime.getMillis - tStart.getMillis)
    val uriIdOpt = nUriOpt.flatMap(_.id)
    val (thread, isNew) = db.readWrite{ implicit session =>
      val (thread, isNew) = threadRepo.getOrCreate(userParticipants, nonUserRecipients, urlOpt, uriIdOpt, nUriOpt.map(_.url), titleOpt.orElse(nUriOpt.flatMap(_.title)))
      if (isNew){
        nonUserRecipients.par.foreach { nonUser =>
          NonUserThread(                              // will have to be persisted when extension is ready
            participant = nonUser,
            threadId = thread.id.get,
            uriId = uriIdOpt,
            notifiedCount = 0,
            lastNotifiedAt = None,
            threadUpdatedAt = Some(thread.createdAt),
            muted = false
          )
        }
        userParticipants.par.foreach { userId =>
          userThreadRepo.save(UserThread(
            user = userId,
            thread = thread.id.get,
            uriId = uriIdOpt,
            lastSeen = None,
            lastMsgFromOther = None,
            lastNotification = JsNull,
            unread = false,
            started = userId == from
          ))
        }
      }
      else{
        log.info(s"Not actually a new thread. Merging.")
      }
      (thread, isNew)
    }
    sendMessage(from, thread, messageText, urlOpt, nUriOpt, Some(isNew))
  }


  def sendMessage(from: Id[User], threadId: ExternalId[MessageThread], messageText: String, urlOpt: Option[String])(implicit context: HeimdalContext): (MessageThread, Message) = {
    val thread = db.readOnly{ implicit session =>
      threadRepo.get(threadId)
    }
    sendMessage(from, thread, messageText, urlOpt)
  }

  def sendMessage(from: Id[User], threadId: Id[MessageThread], messageText: String, urlOpt: Option[String])(implicit context: HeimdalContext): (MessageThread, Message) = {
    val thread = db.readOnly{ implicit session =>
      threadRepo.get(threadId)
    }
    sendMessage(from, thread, messageText, urlOpt)
  }

  private def sendMessage(from: Id[User], thread: MessageThread, messageText: String, urlOpt: Option[String], nUriOpt: Option[NormalizedURI] = None, isNew: Option[Boolean] = None)(implicit context: HeimdalContext): (MessageThread, Message) = {
    if (! thread.containsUser(from) || !thread.replyable) throw NotAuthorizedException(s"User $from not authorized to send message on thread ${thread.id.get}")
    log.info(s"Sending message from $from to ${thread.participants}")
    val message = db.readWrite{ implicit session =>
      messageRepo.save(Message(
        id = None,
        from = Some(from),
        thread = thread.id.get,
        threadExtId = thread.externalId,
        messageText = messageText,
        sentOnUrl = urlOpt.map(Some(_)).getOrElse(thread.url),
        sentOnUriId = thread.uriId
      ))
    }
    SafeFuture {
      db.readOnly { implicit session => messageRepo.refreshCache(thread.id.get) }
    }

    val participantSet = thread.participants.map(_.allUsers).getOrElse(Set())
    val id2BasicUser = Await.result(shoebox.getBasicUsers(participantSet.toSeq), 1 seconds) // todo: remove await

    val messageWithBasicUser = MessageWithBasicUser(
      message.externalId,
      message.createdAt,
      message.messageText,
      None,
      message.sentOnUrl.getOrElse(""),
      thread.nUrl.getOrElse(""), //TODO Stephen: This needs to change when we have detached threads
      message.from.map(id2BasicUser(_)),
      participantSet.toSeq.map(id2BasicUser(_))
    )

    thread.participants.map(_.allUsers.par.foreach { user =>
      notificationCommander.sendToUser(user, Json.arr("message", message.threadExtId.id, messageWithBasicUser))
    })

    setLastSeen(from, thread.id.get, Some(message.createdAt))
    db.readWrite { implicit session => userThreadRepo.setLastActive(from, thread.id.get, message.createdAt) }

    val (numMessages: Int, numUnread: Int, threadActivity: Seq[UserThreadActivity]) = db.readOnly { implicit session =>
      val (numMessages, numUnread) = messageRepo.getMessageCounts(thread.id.get, Some(message.createdAt))
      val threadActivity = userThreadRepo.getThreadActivity(thread.id.get).sortBy { uta =>
        (-uta.lastActive.getOrElse(START_OF_TIME).getMillis, uta.id.id)
      }
      (numMessages, numUnread, threadActivity)
    }

    val originalAuthor = threadActivity.filter(_.started).zipWithIndex.head._2
    val numAuthors = threadActivity.count(_.lastActive.isDefined)

    val nonUsers = thread.participants.map(_.allNonUsers.map(NonUserParticipant.toBasicNonUser)).getOrElse(Set.empty)
    val orderedMessageWithBasicUser = messageWithBasicUser.copy(participants = threadActivity.map{ ta => id2BasicUser(ta.userId)} ++ nonUsers)

    thread.allParticipantsExcept(from).foreach { userId =>
      notificationCommander.sendNotificationForMessage(userId, message, thread, orderedMessageWithBasicUser, threadActivity, getUnreadUnmutedThreadCount(userId))
    }


    val notifJson = notificationCommander.buildMessageNotificationJson(
      message = message,
      thread = thread,
      messageWithBasicUser = orderedMessageWithBasicUser,
      locator = "/messages/" + thread.externalId,
      unread = false,
      originalAuthorIdx = originalAuthor,
      unseenAuthors = 0,
      numAuthors = numAuthors,
      numMessages = numMessages,
      numUnread = numUnread,
      muted = false)
    db.readWrite(attempts=2) { implicit session =>
      userThreadRepo.setNotification(from, thread.id.get, message, notifJson, false)
    }
    notificationCommander.sendToUser(from, Json.arr("notification", notifJson))


    //async update normalized url id so as not to block on that (the shoebox call yields a future)
    urlOpt.foreach { url =>
      (nUriOpt match {
        case Some(n) => Promise.successful(n).future
        case None => shoebox.internNormalizedURI(url, scrapeWanted = true) //Note, this also needs to include canonical/og when we have detached threads
      }) foreach { nUri =>
        db.readWrite { implicit session =>
          messageRepo.updateUriId(message, nUri.id.get)
        }
      }
    }
    messagingAnalytics.sentMessage(from, message, thread, isNew, context)
    (thread, message)
  }

  def getThreadMessages(thread: MessageThread, pageOpt: Option[Int]) : Seq[Message] =
    db.readOnly { implicit session =>
      log.info(s"[get_thread] trying to get thread messages for thread extId ${thread.externalId}. pageOpt is $pageOpt")
      pageOpt.map { page =>
        val lower = MessagingCommander.THREAD_PAGE_SIZE * page
        val upper = MessagingCommander.THREAD_PAGE_SIZE * (page + 1) - 1
        log.info(s"[get_thread] getting thread messages for thread extId ${thread.externalId}. lu: $lower, $upper")
        messageRepo.get(thread.id.get,lower,Some(upper))
      } getOrElse {
        log.info(s"[get_thread] getting thread messages for thread extId ${thread.externalId}. no l/u")
        messageRepo.get(thread.id.get, 0, None)
      }
    }


  private def getThreadMessages(threadExtId: ExternalId[MessageThread], pageOpt: Option[Int]) : Seq[Message] = {
    val thread = db.readOnly{ implicit session =>
      threadRepo.get(threadExtId)
    }
    getThreadMessages(thread, pageOpt)
  }

  private def getThreadMessages(threadId: Id[MessageThread], pageOpt: Option[Int]): Seq[Message] = {
    getThreadMessages(db.readOnly(threadRepo.get(threadId)(_)), pageOpt)
  }

  private def getThreadMessagesWithBasicUser(thread: MessageThread, pageOpt: Option[Int]): Future[(MessageThread, Seq[MessageWithBasicUser])] = {
    val userParticipantSet = if (thread.replyable) thread.participants.map(_.allUsers).getOrElse(Set()) else Set()
    log.info(s"[get_thread] got participants for extId ${thread.externalId}: $userParticipantSet")
    new SafeFuture(shoebox.getBasicUsers(userParticipantSet.toSeq) map { id2BasicUser =>
      val messages = getThreadMessages(thread, pageOpt)
      log.info(s"[get_thread] got raw messages for extId ${thread.externalId}: ${messages.length}")
      (thread, messages.map { message =>
        val nonUsers = thread.participants.map(_.allNonUsers.map(NonUserParticipant.toBasicNonUser)).getOrElse(Set.empty)
        MessageWithBasicUser(
          id           = message.externalId,
          createdAt    = message.createdAt,
          text         = message.messageText,
          auxData      = message.auxData,
          url          = message.sentOnUrl.getOrElse(""),
          nUrl         = thread.nUrl.getOrElse(""), //TODO Stephen: This needs to change when we have detached threads
          user         = message.from.map(id2BasicUser(_)),
          participants = userParticipantSet.toSeq.map(id2BasicUser(_)) ++ nonUsers
        )
      })
    })
  }

  def getThreadMessagesWithBasicUser(threadExtId: ExternalId[MessageThread], pageOpt: Option[Int]): Future[(MessageThread, Seq[MessageWithBasicUser])] = {
    val thread = db.readOnly(threadRepo.get(threadExtId)(_))
    getThreadMessagesWithBasicUser(thread, pageOpt)
  }

  def getThread(threadExtId: ExternalId[MessageThread]) : MessageThread = {
    db.readOnly { implicit session =>
      threadRepo.get(threadExtId)
    }
  }

  def getThreads(user: Id[User], url: Option[String]=None) : Seq[MessageThread] = {
    db.readOnly { implicit session =>
      val threadIds = userThreadRepo.getThreadIds(user)
      threadIds.map(threadRepo.get(_))
    }
  }

  // todo: Add adding non-users by kind/identifier
  def addParticipantsToThread(adderUserId: Id[User], threadExtId: ExternalId[MessageThread], newParticipantsExtIds: Seq[ExternalId[User]])(implicit context: HeimdalContext): Future[Boolean] = {
    new SafeFuture(shoebox.getUserIdsByExternalIds(newParticipantsExtIds) map { newParticipantsUserIds =>

      val messageThreadOpt = db.readWrite { implicit session =>
        val oldThread = threadRepo.get(threadExtId)
        val actuallyNewParticipantUserIds = newParticipantsUserIds.filterNot(oldThread.containsUser)

        if (!oldThread.participants.exists(_.contains(adderUserId)) || actuallyNewParticipantUserIds.isEmpty) {
          None
        } else {
          val thread = threadRepo.save(oldThread.withParticipants(clock.now, actuallyNewParticipantUserIds))

          val message = messageRepo.save(Message(
            from = None,
            thread = thread.id.get,
            threadExtId = thread.externalId,
            messageText = "",
            auxData = Some(Json.arr("add_participants", adderUserId.id.toString, actuallyNewParticipantUserIds.map(_.id))),
            sentOnUrl = None,
            sentOnUriId = None
          ))
          Some((actuallyNewParticipantUserIds, message, thread))
        }
      }

      messageThreadOpt.exists { case (newParticipants, message, thread) =>
        SafeFuture {
          db.readOnly { implicit session =>
            messageRepo.refreshCache(thread.id.get)
          }
        }

        new SafeFuture(shoebox.getBasicUsers(thread.participants.get.allUsers.toSeq) map { basicUsers =>
          val adderUserName = basicUsers(adderUserId).firstName + " " + basicUsers(adderUserId).lastName
          val theTitle: String = thread.pageTitle.getOrElse("New conversation")
          val participants: Seq[BasicUserLikeEntity] = basicUsers.values.toSeq ++ thread.participants.get.allNonUsers.map(NonUserParticipant.toBasicNonUser).toSeq
          val notificationJson = Json.obj(
            "id"           -> message.externalId.id,
            "time"         -> message.createdAt,
            "thread"       -> thread.externalId.id,
            "text"         -> s"$adderUserName added you to a conversation.",
            "url"          -> thread.nUrl,
            "title"        -> theTitle,
            "author"       -> basicUsers(adderUserId),
            "participants" -> participants,
            "locator"      -> ("/messages/" + thread.externalId),
            "unread"       -> true,
            "category"     -> NotificationCategory.User.MESSAGE.category
          )
          db.readWrite { implicit session =>
            // todo: Add adding non-users
            newParticipants.map { pUserId =>
              userThreadRepo.save(UserThread(
                id = None,
                user = pUserId,
                thread = thread.id.get,
                uriId = thread.uriId,
                lastSeen = None,
                unread = true,
                lastMsgFromOther = None,
                lastNotification = JsNull
              ))
            }
          }

          Future.sequence(newParticipants.map { userId =>
            notificationCommander.recreateNotificationForAddedParticipant(userId, thread)
          }) map { permanentNotifications =>
            newParticipants.zip(permanentNotifications) map { case (userId, permanentNotification) =>
              notificationCommander.sendToUser(
                userId, Json.arr("notification", notificationJson, permanentNotification)
              )
            }
            val mwbu = MessageWithBasicUser(message.externalId, message.createdAt, "", message.auxData, "", "", None, participants)
            modifyMessageWithAuxData(mwbu).map { augmentedMessage =>
              thread.participants.map(_.allUsers.par.foreach { userId =>
                notificationCommander.sendToUser(
                  userId, Json.arr("message", thread.externalId.id, augmentedMessage)
                )
                notificationCommander.sendToUser(userId, Json.arr("thread_participants", thread.externalId.id, participants))
              })
            }
          }
        })

        messagingAnalytics.addedParticipantsToConversation(adderUserId, newParticipants, thread, context)
        true
      }
    }, Some("Adding Participants to Thread"))
  }

  def modifyMessageWithAuxData(m: MessageWithBasicUser): Future[MessageWithBasicUser] = {
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

  def setRead(userId: Id[User], msgExtId: ExternalId[Message])(implicit context: HeimdalContext): Unit = {
    val (message: Message, thread: MessageThread) = db.readOnly { implicit session =>
      val message = messageRepo.get(msgExtId)
      (message, threadRepo.get(message.thread))
    }
    db.readWrite(attempts=2) { implicit session =>
      userThreadRepo.markRead(userId, thread.id.get, message)
    }
    messagingAnalytics.clearedNotification(userId, message, thread, context)

    notificationCommander.notifyRead(userId, thread.externalId, msgExtId, thread.nUrl.getOrElse(""), message.createdAt, getUnreadUnmutedThreadCount(userId))
  }

  def setUnread(userId: Id[User], msgExtId: ExternalId[Message]): Unit = {
    val (message: Message, thread: MessageThread) = db.readOnly { implicit session =>
      val message = messageRepo.get(msgExtId)
      (message, threadRepo.get(message.thread))
    }
    val changed: Boolean = db.readWrite(attempts=2) { implicit session =>
      userThreadRepo.markUnread(userId, thread.id.get)
    }
    if (changed) {
      notificationCommander.notifyUnread(userId, thread.externalId, msgExtId, thread.nUrl.getOrElse(""), message.createdAt, getUnreadUnmutedThreadCount(userId))
    }
  }

  def setLastSeen(userId: Id[User], threadId: Id[MessageThread], timestampOpt: Option[DateTime] = None) : Unit = {
    db.readWrite { implicit session =>
      userThreadRepo.setLastSeen(userId, threadId, timestampOpt.getOrElse(clock.now))
    }
  }

  def setLastSeen(userId: Id[User], messageExtId: ExternalId[Message]) : Unit = {
    val message = db.readOnly { implicit session => messageRepo.get(messageExtId) }
    setLastSeen(userId, message.thread, Some(message.createdAt))
  }

  def getUnreadUnmutedThreadCount(userId: Id[User]): Int = {
    db.readOnly { implicit session =>
      userThreadRepo.getUnreadUnmutedThreadCount(userId)
    }
  }

  def getUnreadThreadCounts(userId: Id[User]): (Int, Int) = {
    db.readOnly { implicit session =>
      userThreadRepo.getUnreadThreadCounts(userId)
    }
  }

  def muteThread(userId: Id[User], threadId: ExternalId[MessageThread])(implicit context: HeimdalContext): Boolean = setUserThreadMuteState(userId, threadId, true)

  def unmuteThread(userId: Id[User], threadId: ExternalId[MessageThread])(implicit context: HeimdalContext): Boolean = setUserThreadMuteState(userId, threadId, false)

  private def setUserThreadMuteState(userId: Id[User], threadId: ExternalId[MessageThread], mute: Boolean)(implicit context: HeimdalContext) = {
    val stateChanged = db.readWrite { implicit session =>
      val thread = threadRepo.get(threadId)
      thread.id.exists { threadId =>
        val userThread = userThreadRepo.getUserThread(userId, threadId)
        if (userThread.muted != mute) {
          userThreadRepo.setMuteState(userThread.id.get, mute)
          true
        } else {
          false
        }
      }
    }
    if (stateChanged) {
      notificationCommander.notifyUserAboutMuteChange(userId, threadId, mute)
      messagingAnalytics.changedMute(userId, threadId, mute, context)
    }
    stateChanged
  }

  def getChatter(userId: Id[User], urls: Seq[String]) = {
    implicit val timeout = Duration(3, "seconds")
    TimeoutFuture(Future.sequence(urls.map(u => shoebox.getNormalizedURIByURL(u).map(n => u -> n)))).recover {
      case ex: TimeoutException => Seq[(String, Option[NormalizedURI])]()
    }.map { res =>
      val urlMsgCount = db.readOnly { implicit session =>
        res.filter(_._2.isDefined).map { case (url, nuri) =>
          url -> userThreadRepo.getThreadIds(userId, Some(nuri.get.id.get))
        }
      }
      Map(urlMsgCount: _*)
    }
  }

  def sendMessageAction(title: Option[String], text: String,
      userExtRecipients: Seq[ExternalId[User]],
      nonUserRecipients: Seq[NonUserParticipant],
      url: Option[String],
      urls: JsObject, //wtf its not Seq[String]?
      userId: Id[User],
      context: HeimdalContext): Future[(Message, Option[ElizaThreadInfo], Seq[MessageWithBasicUser])] = {
    val tStart = currentDateTime

    val res = for {
      userRecipients <- constructUserRecipients(userExtRecipients)
      nonUserRecipients <- constructNonUserRecipients(userId, nonUserRecipients)
    } yield {
      val (thread, message) = sendNewMessage(userId, userRecipients, nonUserRecipients, urls, title, text)(context)
      val messageThreadFut = getThreadMessagesWithBasicUser(thread, None)

      val threadInfoOpt = url.map { url =>
        buildThreadInfos(userId, Seq(thread), Some(url)).headOption
      }.flatten

      messageThreadFut.map { case (_, messages) =>
        val tDiff = currentDateTime.getMillis - tStart.getMillis
        Statsd.timing(s"messaging.newMessage", tDiff)
        (message, threadInfoOpt, messages)
      }
    }
    res.flatMap{ fut => fut.flatMap { case (message, threadInfoOpt, messages) =>
        Future.sequence(messages.map(modifyMessageWithAuxData)).map( (message, threadInfoOpt, _) )
      }
    }
  }

  def recipientJsonToTypedFormat(rawRecipients: Seq[JsValue]): (Seq[ExternalId[User]], Seq[NonUserParticipant]) = {
    rawRecipients.foldLeft((Seq[ExternalId[User]](), Seq[NonUserParticipant]())) { case ((externalUserIds, nonUserParticipants), elem) =>
      elem.asOpt[String].flatMap(ExternalId.asOpt[User]) match {
        case Some(externalUserId) => (externalUserIds :+ externalUserId, nonUserParticipants)
        case None =>
          elem.asOpt[JsObject].flatMap { obj =>
            // The strategy is to get the identifier in the correct wrapping type, and pimp it with `constructNonUserRecipients` later
            (obj \ "kind").asOpt[String] match {
              case Some("email") if (obj \ "email").asOpt[String].isDefined =>
                Some(NonUserEmailParticipant(GenericEmailAddress((obj \ "email").as[String]), None))
              case _ => // Unsupported kind
                None
            }
          } match {
            case Some(nonUser) =>
              (externalUserIds, nonUserParticipants :+ nonUser)
            case None =>
              (externalUserIds, nonUserParticipants)
          }
      }
    }
  }
}
