package com.keepit.eliza.commanders

import com.google.inject.Inject

import com.keepit.abook.ABookServiceClient
import com.keepit.common.crypto.{ PublicIdConfiguration, PublicId }
import com.keepit.common.net.URI
import com.keepit.eliza.{ SimplePushNotificationCategory, LibraryPushNotificationCategory, UserPushNotificationCategory, PushNotificationExperiment }
import com.keepit.eliza.model._
import com.keepit.common.akka.{ SafeFuture, TimeoutFuture }
import com.keepit.common.db.{ Id, ExternalId }
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.mail.BasicContact
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.time._
import com.keepit.heimdal.{ HeimdalContextBuilder, HeimdalContext }
import com.keepit.model._
import com.keepit.notify.delivery.{ WsNotificationDelivery, NotificationJsonFormat }
import com.keepit.notify.model.event.NewMessage
import com.keepit.notify.model.Recipient
import com.keepit.realtime.{ UserPushNotification, LibraryUpdatePushNotification, SimplePushNotification }
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.social.{ BasicUserLikeEntity, NonUserKinds }
import com.keepit.common.concurrent.PimpMyFuture._
import scala.util.Try

import org.joda.time.DateTime

import play.api.libs.json._

import scala.concurrent.{ ExecutionContext, Promise, Await, Future }
import scala.concurrent.duration._
import java.util.concurrent.TimeoutException
import com.keepit.common.db.slick.DBSession.RSession

import scala.util.{ Success, Failure }

case class NotAuthorizedException(msg: String) extends java.lang.Throwable(msg)

object MessagingCommander {
  val THREAD_PAGE_SIZE = 20

  val MAX_RECENT_NON_USER_RECIPIENTS = 100
  val WARNING_RECENT_NON_USER_RECIPIENTS = 75
  val RECENT_NON_USER_RECIPIENTS_WINDOW = 24 hours
  val maxRecentEmailRecipientsErrorMessage = s"You are allowed ${MessagingCommander.MAX_NON_USER_PARTICIPANTS_PER_THREAD} email recipients per discussion."

  val MAX_NON_USER_PARTICIPANTS_PER_THREAD = 30
  val WARNING_NON_USER_PARTICIPANTS_PER_THREAD = 20

  def maxEmailRecipientsPerThreadErrorMessage(user: Id[User], emailCount: Int) = s"You (user #$user) have hit the limit on the number of emails ($emailCount) you are able to send through Kifi."
}

class MessagingCommander @Inject() (
    threadRepo: MessageThreadRepo,
    userThreadRepo: UserThreadRepo,
    nonUserThreadRepo: NonUserThreadRepo,
    messageRepo: MessageRepo,
    db: Database,
    clock: Clock,
    abookServiceClient: ABookServiceClient,
    messagingAnalytics: MessagingAnalytics,
    shoebox: ShoeboxServiceClient,
    airbrake: AirbrakeNotifier,
    notificationRepo: NotificationRepo,
    basicMessageCommander: MessageFetchingCommander,
    notificationCommander: NotificationCommander,
    notificationDeliveryCommander: NotificationDeliveryCommander,
    messageSearchHistoryRepo: MessageSearchHistoryRepo,
    implicit val executionContext: ExecutionContext,
    implicit val publicIdConfig: PublicIdConfiguration) extends Logging {

  def sendUserPushNotification(userId: Id[User], message: String, recipientUserId: ExternalId[User], username: Username, pictureUrl: String, pushNotificationExperiment: PushNotificationExperiment, category: UserPushNotificationCategory): Future[Int] = {
    val notification = UserPushNotification(message = Some(message), userExtId = recipientUserId, username = username, pictureUrl = pictureUrl, unvisitedCount = getUnreadUnmutedThreadCount(userId), category = category, experiment = pushNotificationExperiment)
    notificationDeliveryCommander.sendPushNotification(userId, notification)
  }

  def sendLibraryPushNotification(userId: Id[User], message: String, libraryId: Id[Library], libraryUrl: String, pushNotificationExperiment: PushNotificationExperiment, category: LibraryPushNotificationCategory, force: Boolean): Future[Int] = {
    val notification = LibraryUpdatePushNotification(message = Some(message), libraryId = libraryId, libraryUrl = libraryUrl, unvisitedCount = getUnreadUnmutedThreadCount(userId), category = category, experiment = pushNotificationExperiment)
    notificationDeliveryCommander.sendPushNotification(userId, notification, force)
  }

  def sendGeneralPushNotification(userId: Id[User], message: String, pushNotificationExperiment: PushNotificationExperiment, category: SimplePushNotificationCategory, force: Boolean): Future[Int] = {
    val notification = SimplePushNotification(message = Some(message), unvisitedCount = getUnreadUnmutedThreadCount(userId), category = category, experiment = pushNotificationExperiment)
    notificationDeliveryCommander.sendPushNotification(userId, notification, force)
  }

  private def buildThreadInfos(userId: Id[User], threads: Seq[MessageThread], requestUrl: Option[String]): Future[Seq[ElizaThreadInfo]] = {
    //get all involved users
    val allInvolvedUsers = threads.flatMap { _.participants.map(_.allUsers).getOrElse(Set()) }
    //get all basic users
    val userId2BasicUserF = shoebox.getBasicUsers(allInvolvedUsers.toSeq)
    //get all messages
    val messagesByThread: Map[Id[MessageThread], Seq[Message]] = threads.map { thread =>
      (thread.id.get, basicMessageCommander.getThreadMessages(thread))
    }.toMap
    //get user_threads
    val userThreads: Map[Id[MessageThread], UserThread] = db.readOnlyMaster { implicit session =>
      threads.map { thread =>
        (thread.id.get, userThreadRepo.getUserThread(userId, thread.id.get))
      }
    }.toMap

    userId2BasicUserF.map { userId2BasicUser =>
      threads.map { thread =>

        val lastMessageOpt = messagesByThread(thread.id.get).collectFirst { case m if m.from.asUser.isDefined => m }
        if (lastMessageOpt.isEmpty) log.error(s"EMPTY THREAD! thread_id: ${thread.id.get} request_url: $requestUrl user: $userId")
        val lastMessage = lastMessageOpt.get

        val messageTimes = messagesByThread(thread.id.get).take(10).map { message =>
          (message.externalId, message.createdAt)
        }.toMap

        val nonUsers = thread.participants.map(_.allNonUsers).getOrElse(Set())
          .map(nu => BasicUserLikeEntity(NonUserParticipant.toBasicNonUser(nu))).toSeq

        val basicUsers = thread.participants.map(_.allUsers).getOrElse(Set())
          .map(u => BasicUserLikeEntity(userId2BasicUser(u))).toSeq

        ElizaThreadInfo(
          externalId = thread.externalId,
          participants = basicUsers ++ nonUsers,
          digest = lastMessage.messageText,
          lastAuthor = userId2BasicUser(lastMessage.from.asUser.get).externalId,
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
  }

  def getThreadInfos(userId: Id[User], url: String): Future[(String, Seq[ElizaThreadInfo])] = {
    new SafeFuture(shoebox.getNormalizedUriByUrlOrPrenormalize(url).flatMap {
      case Left(nUri) =>
        val threads = db.readOnlyReplica { implicit session =>
          val threadIds = userThreadRepo.getThreadIds(userId, nUri.id)
          threadIds.map(threadRepo.get)
        }.filter(_.replyable)
        buildThreadInfos(userId, threads, Some(url)).map { unsortedInfos =>
          val infos = unsortedInfos sortWith { (a, b) =>
            a.lastCommentedAt.compareTo(b.lastCommentedAt) < 0
          }
          (nUri.url, infos)
        }
      case Right(prenormalizedUrl) =>
        Future.successful((prenormalizedUrl, Seq[ElizaThreadInfo]()))
    })
  }

  def keepAttribution(userId: Id[User], uriId: Id[NormalizedURI]): Seq[Id[User]] = {
    val threads = db.readOnlyReplica { implicit session =>
      userThreadRepo.getUserThreads(userId, uriId)
    }
    val otherStarters = threads.filter { userThread =>
      userThread.lastSeen.exists(dt => dt.plusDays(3).isAfterNow) // tweak
    } map { userThread =>
      db.readOnlyReplica { implicit session =>
        userThreadRepo.getThreadStarter(userThread.threadId)
      }
    } filter { _ != userId }
    log.info(s"[keepAttribution($userId,$uriId)] threads=${threads.map(_.id.get)} otherStarters=$otherStarters")
    otherStarters
  }

  def hasThreads(userId: Id[User], url: String): Future[Boolean] = {
    shoebox.getNormalizedURIByURL(url).map {
      case Some(nUri) => db.readOnlyReplica { implicit session => userThreadRepo.hasThreads(userId, nUri.id.get) }
      case None => false
    }
  }

  def checkUrisDiscussed(userId: Id[User], uriIds: Seq[Id[NormalizedURI]]): Future[Seq[Boolean]] = {
    db.readOnlyReplicaAsync { implicit session => userThreadRepo.checkUrisDiscussed(userId, uriIds) }
  }

  def deleteUserThreadsForMessageId(id: Id[Message]): Unit = {
    val (threadExtId, userThreads): (ExternalId[MessageThread], Seq[UserThread]) = db.readOnlyMaster { implicit session =>
      val message = messageRepo.get(id)
      val threadId = message.thread
      (message.threadExtId, userThreadRepo.getByThread(threadId))
    }
    if (userThreads.length != 1) {
      airbrake.notify(s"Trying to delete notification for thread $threadExtId with not exactly one participant. Not permitted.")
    } else {
      userThreads.foreach { userThread =>
        db.readWrite { implicit session =>
          userThreadRepo.delete(userThread)
        }
        notificationDeliveryCommander.notifyRemoveThread(userThread.user, threadExtId)
      }
    }
  }

  private def constructNonUserRecipients(userId: Id[User], nonUsers: Seq[BasicContact]): Future[Seq[NonUserParticipant]] = {
    abookServiceClient.internKifiContacts(userId, nonUsers: _*).map { richContacts =>
      richContacts.map(richContact => NonUserEmailParticipant(richContact.email))
    }
  }

  private def updateMessageSearchHistoryWithEmailAddresses(userId: Id[User], nups: Seq[NonUserParticipant]) = {
    SafeFuture("adding email address to message search history") {
      db.readWrite { implicit session =>
        val history = messageSearchHistoryRepo.getOrCreate(userId)
        if (!history.optOut) {
          messageSearchHistoryRepo.save(history.withNewEmails(nups.filter(_.kind == NonUserKinds.email).map(_.identifier)))
        }
      }
    }
  }

  def sendNewMessage(from: Id[User], userRecipients: Seq[Id[User]], nonUserRecipients: Seq[NonUserParticipant], url: String, titleOpt: Option[String], messageText: String, source: Option[MessageSource])(implicit context: HeimdalContext): Future[(MessageThread, Message)] = {
    updateMessageSearchHistoryWithEmailAddresses(from, nonUserRecipients)
    val userParticipants = (from +: userRecipients).distinct
    val tStart = currentDateTime

    val uriFetch = URI.parse(url) match {
      case Success(parsed) =>
        shoebox.internNormalizedURI(parsed.toString(), contentWanted = true).map(n => (parsed, n.id.get, n.url, n.title))
      case Failure(e) => throw new Exception(s"can't send message for bad URL: [$url] from $from with title $titleOpt and source $source")
    }

    uriFetch.map {
      case ((uri, nUriId, nUrl, nTitleOpt)) =>
        statsd.timing(s"messaging.internNormalizedURI", currentDateTime.getMillis - tStart.getMillis, ONE_IN_THOUSAND)

        val (thread, isNew) = db.readWrite { implicit session =>
          val (thread, isNew) = threadRepo.getOrCreate(userParticipants, nonUserRecipients, Some(url), Some(nUriId), Some(nUrl), titleOpt.orElse(nTitleOpt))
          if (isNew) {
            checkEmailParticipantRateLimits(from, thread, nonUserRecipients)
            nonUserRecipients.foreach { nonUser =>
              nonUserThreadRepo.save(NonUserThread(
                createdBy = from,
                participant = nonUser,
                threadId = thread.id.get,
                uriId = Some(nUriId),
                notifiedCount = 0,
                lastNotifiedAt = None,
                threadUpdatedByOtherAt = Some(thread.createdAt),
                muted = false
              ))
            }
            userParticipants.foreach { userId =>
              userThreadRepo.save(UserThread(
                user = userId,
                threadId = thread.id.get,
                uriId = Some(nUriId),
                lastSeen = None,
                lastMsgFromOther = None,
                lastNotification = JsNull,
                unread = false,
                started = userId == from
              ))
            }
          } else {
            log.info(s"Not actually a new thread. Merging.")
          }
          (thread, isNew)
        }

        //this is code for a special status message used in the client to do the email preview
        if (nonUserRecipients.nonEmpty) {
          db.readWrite { implicit session =>
            messageRepo.save(Message(
              from = MessageSender.System,
              thread = thread.id.get,
              threadExtId = thread.externalId,
              messageText = "",
              source = source,
              auxData = Some(Json.arr("start_with_emails", from.id.toString,
                userRecipients.map(u => Json.toJson(u.id)) ++ nonUserRecipients.map(Json.toJson(_))
              )),
              sentOnUrl = None,
              sentOnUriId = None
            ))
          }
        }

        sendMessage(MessageSender.User(from), thread, messageText, source, Some(uri), Some(nUriId), Some(isNew))
    }

  }

  def sendMessageWithNonUserThread(nut: NonUserThread, messageText: String, source: Option[MessageSource], urlOpt: Option[URI])(implicit context: HeimdalContext): (MessageThread, Message) = {
    log.info(s"Sending message from non-user with id ${nut.id} to thread ${nut.threadId}")
    val thread = db.readOnlyMaster { implicit session => threadRepo.get(nut.threadId) }
    sendMessage(MessageSender.NonUser(nut.participant), thread, messageText, source, urlOpt)
  }

  def sendMessageWithUserThread(userThread: UserThread, messageText: String, source: Option[MessageSource], urlOpt: Option[URI])(implicit context: HeimdalContext): (MessageThread, Message) = {
    log.info(s"Sending message from user with id ${userThread.user} to thread ${userThread.threadId}")
    val thread = db.readOnlyMaster { implicit session => threadRepo.get(userThread.threadId) }
    sendMessage(MessageSender.User(userThread.user), thread, messageText, source, urlOpt)
  }

  def sendMessage(from: Id[User], threadId: ExternalId[MessageThread], messageText: String, source: Option[MessageSource], urlOpt: Option[URI])(implicit context: HeimdalContext): (MessageThread, Message) = {
    val thread =
      db.readOnlyMaster { implicit session =>
        threadRepo.get(threadId)
      }

    sendMessage(MessageSender.User(from), thread, messageText, source, urlOpt)
  }

  def sendMessage(from: Id[User], threadId: Id[MessageThread], messageText: String, source: Option[MessageSource], urlOpt: Option[URI])(implicit context: HeimdalContext): (MessageThread, Message) = {
    val thread = db.readOnlyMaster { implicit session =>
      threadRepo.get(threadId)
    }
    sendMessage(MessageSender.User(from), thread, messageText, source, urlOpt)
  }

  private def sendMessage(from: MessageSender, thread: MessageThread, messageText: String, source: Option[MessageSource], urlOpt: Option[URI], nUriIdOpt: Option[Id[NormalizedURI]] = None, isNew: Option[Boolean] = None)(implicit context: HeimdalContext): (MessageThread, Message) = {
    from match {
      case MessageSender.User(id) =>
        if (!thread.containsUser(id) || !thread.replyable) throw NotAuthorizedException(s"User $id not authorized to send message on thread ${thread.id.get}")
      case MessageSender.NonUser(nup) =>
        if (!thread.containsNonUser(nup) || !thread.replyable) throw NotAuthorizedException(s"Non-User $nup not authorized to send message on thread ${thread.id.get}")
      case MessageSender.System =>
        throw NotAuthorizedException("Wrong code path for system Messages.")
    }

    log.info(s"Sending message from $from to ${thread.participants}")
    val message = db.readWrite { implicit session =>
      messageRepo.save(Message(
        id = None,
        from = from,
        thread = thread.id.get,
        threadExtId = thread.externalId,
        messageText = messageText,
        source = source,
        sentOnUrl = urlOpt.map(_.toString()).orElse(thread.url),
        sentOnUriId = thread.uriId
      ))
    }
    SafeFuture {
      db.readOnlyMaster { implicit session => messageRepo.refreshCache(thread.id.get) }
    }

    val participantSet = thread.participants.map(_.allUsers).getOrElse(Set())
    val nonUserParticipantsSet = thread.participants.map(_.allNonUsers).getOrElse(Set())
    val id2BasicUser = Await.result(shoebox.getBasicUsers(participantSet.toSeq), 1.seconds) // todo: remove await
    val basicNonUserParticipants = nonUserParticipantsSet.map(NonUserParticipant.toBasicNonUser)
      .map(nu => BasicUserLikeEntity(nu))

    val messageWithBasicUser = MessageWithBasicUser(
      message.externalId,
      message.createdAt,
      message.messageText,
      source,
      None,
      message.sentOnUrl.getOrElse(""),
      thread.nUrl.getOrElse(""), //TODO Stephen: This needs to change when we have detached threads
      message.from match {
        case MessageSender.User(id) => Some(BasicUserLikeEntity(id2BasicUser(id)))
        case MessageSender.NonUser(nup) => Some(BasicUserLikeEntity(NonUserParticipant.toBasicNonUser(nup)))
        case _ => None
      },
      participantSet.toSeq.map(u => BasicUserLikeEntity(id2BasicUser(u))) ++ basicNonUserParticipants
    )

    // send message through websockets immediately
    thread.participants.foreach(_.allUsers.foreach { user =>
      notificationDeliveryCommander.notifyMessage(user, thread, messageWithBasicUser)
    })

    // update user thread of the sender
    from.asUser.foreach { sender =>
      setLastSeen(sender, thread.id.get, Some(message.createdAt))
      db.readWrite { implicit session => userThreadRepo.setLastActive(sender, thread.id.get, message.createdAt) }
    }

    // update user threads of user recipients - this somehow depends on the sender's user thread update above
    val (numMessages: Int, numUnread: Int, threadActivity: Seq[UserThreadActivity]) = db.readOnlyMaster { implicit session =>
      val (numMessages, numUnread) = messageRepo.getMessageCounts(thread.id.get, Some(message.createdAt))
      val threadActivity = userThreadRepo.getThreadActivity(thread.id.get).sortBy { uta =>
        (-uta.lastActive.getOrElse(START_OF_TIME).getMillis, uta.id.id)
      }
      (numMessages, numUnread, threadActivity)
    }

    val originalAuthor = threadActivity.filter(_.started).zipWithIndex.head._2
    val numAuthors = threadActivity.count(_.lastActive.isDefined)

    val orderedMessageWithBasicUser = messageWithBasicUser.copy(
      participants = threadActivity.map { ta =>
        BasicUserLikeEntity(id2BasicUser(ta.userId))
      } ++ basicNonUserParticipants
    )

    val usersToNotify = from match {
      case MessageSender.User(id) => thread.allParticipantsExcept(id)
      case _ => thread.allParticipants
    }
    usersToNotify.foreach { userId =>
      notificationDeliveryCommander.sendNotificationForMessage(userId, message, thread, orderedMessageWithBasicUser, threadActivity)
    }

    // update user thread of the sender again, might be deprecated
    from.asUser.foreach { sender =>
      notificationDeliveryCommander.notifySendMessage(sender, message, thread, orderedMessageWithBasicUser, originalAuthor, numAuthors, numMessages, numUnread)
    }

    // update non user threads of non user recipients
    notificationDeliveryCommander.updateEmailParticipantThreads(thread, message)
    if (isNew.exists(identity)) { notificationDeliveryCommander.notifyEmailParticipants(thread) }

    //async update normalized url id so as not to block on that (the shoebox call yields a future)
    urlOpt.foreach { url =>
      (nUriIdOpt match {
        case Some(n) => Promise.successful(n).future
        case None => shoebox.internNormalizedURI(url.toString(), contentWanted = true).map(_.id.get) //Note, this also needs to include canonical/og when we have detached threads
      }) foreach { nUriId =>
        db.readWrite { implicit session =>
          messageRepo.updateUriId(message, nUriId)
        }
      }
    }
    messagingAnalytics.sentMessage(from, message, thread, isNew, context)

    (thread, message)
  }

  private def getNonUserThreadOptWithSession(id: Id[NonUserThread])(implicit session: RSession): Option[NonUserThread] = {
    try {
      Some(nonUserThreadRepo.get(id))
    } catch {
      case e: Throwable =>
        airbrake.notify(s"Could not retrieve non-user thread for id $id: ${e.getMessage}")
        None
    }
  }

  def getNonUserThreadOpt(id: Id[NonUserThread]): Option[NonUserThread] =
    db.readOnlyReplica { implicit session => getNonUserThreadOptWithSession(id) }

  def getNonUserThreadOptByAccessToken(token: ThreadAccessToken): Option[NonUserThread] =
    db.readOnlyReplica { implicit session => nonUserThreadRepo.getByAccessToken(token) }

  def getUserThreadOptByAccessToken(token: ThreadAccessToken): Option[UserThread] =
    db.readOnlyReplica { implicit session => userThreadRepo.getByAccessToken(token) }

  def getThreads(user: Id[User], url: Option[String] = None): Seq[MessageThread] = {
    db.readOnlyReplica { implicit session =>
      val threadIds = userThreadRepo.getThreadIds(user)
      threadIds map threadRepo.get
    }
  }

  def addParticipantsToThread(adderUserId: Id[User], threadExtId: ExternalId[MessageThread], newParticipantsExtIds: Seq[ExternalId[User]], emailContacts: Seq[BasicContact], orgs: Seq[PublicId[Organization]])(implicit context: HeimdalContext): Future[Boolean] = {
    val newUserParticipantsFuture = shoebox.getUserIdsByExternalIds(newParticipantsExtIds)
    val newNonUserParticipantsFuture = constructNonUserRecipients(adderUserId, emailContacts)

    val orgIds = orgs.map(o => Organization.decodePublicId(o)).filter(_.isSuccess).map(_.get)
    val newOrgParticipantsFuture = Future.sequence(orgIds.map { oid =>
      shoebox.hasOrganizationMembership(oid, adderUserId).flatMap {
        case true => shoebox.getOrganizationMembers(oid)
        case false => Future.successful(Set.empty[Id[User]])
      }
    }).map(_.flatten)

    val haveBeenAdded = for {
      newUserParticipants <- newUserParticipantsFuture
      newNonUserParticipants <- newNonUserParticipantsFuture
      newOrgParticipants <- newOrgParticipantsFuture
    } yield {

      val resultInfoOpt = db.readWrite { implicit session =>

        val oldThread = threadRepo.get(threadExtId)

        checkEmailParticipantRateLimits(adderUserId, oldThread, newNonUserParticipants)

        if (!oldThread.participants.exists(_.contains(adderUserId)) || !oldThread.replyable) {
          throw NotAuthorizedException(s"User $adderUserId not authorized to add participants to thread ${oldThread.id.get}")
        }

        val actuallyNewUsers = (newUserParticipants ++ newOrgParticipants).filterNot(oldThread.containsUser)
        val actuallyNewNonUsers = newNonUserParticipants.filterNot(oldThread.containsNonUser)

        if (actuallyNewNonUsers.isEmpty && actuallyNewUsers.isEmpty) {
          None
        } else {
          val thread = threadRepo.save(oldThread.withParticipants(clock.now, actuallyNewUsers, actuallyNewNonUsers))
          val message = messageRepo.save(Message(
            from = MessageSender.System,
            thread = thread.id.get,
            threadExtId = thread.externalId,
            messageText = "",
            source = None,
            auxData = Some(Json.arr("add_participants", adderUserId.id.toString,
              actuallyNewUsers.map(u => Json.toJson(u.id)) ++ actuallyNewNonUsers.map(Json.toJson(_))
            )),
            sentOnUrl = None,
            sentOnUriId = None
          ))

          Some((actuallyNewUsers, actuallyNewNonUsers, message, thread))

        }
      }

      resultInfoOpt.exists {
        case (newUsers, newNonUsers, message, thread) =>

          SafeFuture {
            db.readOnlyMaster { implicit session =>
              messageRepo.refreshCache(thread.id.get)
            }
          }

          notificationDeliveryCommander.notifyAddParticipants(newUsers, newNonUsers, thread, message, adderUserId)
          messagingAnalytics.addedParticipantsToConversation(adderUserId, newUsers, newNonUsers, thread, context)
          true

      }

    }

    new SafeFuture[Boolean](haveBeenAdded, Some("Adding Participants to Thread"))
  }

  def setRead(userId: Id[User], msgExtId: ExternalId[Message])(implicit context: HeimdalContext): Unit = {
    val (message: Message, thread: MessageThread) = db.readOnlyMaster { implicit session =>
      val message = messageRepo.get(msgExtId)
      (message, threadRepo.get(message.thread))
    }
    db.readWrite(attempts = 2) { implicit session =>
      userThreadRepo.markRead(userId, thread.id.get, message)
    }
    messagingAnalytics.clearedNotification(userId, message.externalId, thread.externalId, context)

    val unreadMessagesCount = getUnreadUnmutedThreadCount(userId, Some(true))
    val unreadNotificationsCount = getUnreadUnmutedThreadCount(userId, Some(false))
    notificationDeliveryCommander.notifyRead(userId, thread.externalId, msgExtId, thread.nUrl.getOrElse(""), message.createdAt, unreadMessagesCount, unreadNotificationsCount)
  }

  def setUnread(userId: Id[User], msgExtId: ExternalId[Message]): Unit = {
    val (message: Message, thread: MessageThread) = db.readOnlyMaster { implicit session =>
      val message = messageRepo.get(msgExtId)
      (message, threadRepo.get(message.thread))
    }
    val changed: Boolean = db.readWrite(attempts = 2) { implicit session =>
      userThreadRepo.markUnread(userId, thread.id.get)
    }
    if (changed) {
      val unreadMessagesCount = getUnreadUnmutedThreadCount(userId, Some(true))
      val unreadNotificationsCount = getUnreadUnmutedThreadCount(userId, Some(false))
      notificationDeliveryCommander.notifyUnread(userId, thread.externalId, msgExtId, thread.nUrl.getOrElse(""), message.createdAt, unreadMessagesCount, unreadNotificationsCount)
    }
  }

  def setLastSeen(userId: Id[User], threadId: Id[MessageThread], timestampOpt: Option[DateTime] = None): Unit = {
    db.readWrite { implicit session =>
      userThreadRepo.setLastSeen(userId, threadId, timestampOpt.getOrElse(clock.now))
    }
  }

  def setLastSeen(userId: Id[User], messageExtId: ExternalId[Message]): Unit = {
    val message = db.readOnlyMaster { implicit session => messageRepo.get(messageExtId) }
    setLastSeen(userId, message.thread, Some(message.createdAt))
  }

  def getUnreadUnmutedThreadCount(userId: Id[User], filterByReplyable: Option[Boolean] = None): Int = {
    db.readOnlyReplica { implicit session =>
      userThreadRepo.getUnreadUnmutedThreadCount(userId, filterByReplyable) + (filterByReplyable match {
        case Some(true) => notificationRepo.getUnreadNotificationsCountForKind(Recipient(userId), NewMessage.name)
        case Some(false) => notificationRepo.getUnreadNotificationsCountExceptKind(Recipient(userId), NewMessage.name)
        case None => notificationRepo.getUnreadNotificationsCount(Recipient(userId))
      })
    }
  }

  def getUnreadThreadCounts(userId: Id[User]): (Int, Int) = {
    db.readOnlyReplica { implicit session =>
      userThreadRepo.getUnreadThreadCounts(userId) match {
        case (numUnread, numUnmuted) =>
          (
            numUnread + notificationRepo.getNotificationsWithNewEventsCount(Recipient(userId)),
            numUnmuted + notificationRepo.getUnreadNotificationsCount(Recipient(userId))
          )
      }
    }
  }

  def muteThread(userId: Id[User], threadId: ExternalId[MessageThread])(implicit context: HeimdalContext): Boolean = setUserThreadMuteState(userId, threadId, mute = true)

  def unmuteThread(userId: Id[User], threadId: ExternalId[MessageThread])(implicit context: HeimdalContext): Boolean = setUserThreadMuteState(userId, threadId, mute = false)

  def muteThreadForNonUser(id: Id[NonUserThread]): Boolean = setNonUserThreadMuteState(id, mute = true)

  def unmuteThreadForNonUser(id: Id[NonUserThread]): Boolean = setNonUserThreadMuteState(id, mute = false)

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
      notificationDeliveryCommander.notifyUserAboutMuteChange(userId, threadId, mute)
      messagingAnalytics.changedMute(userId, threadId, mute, context)
    }
    stateChanged
  }

  def setNonUserThreadMuteState(id: Id[NonUserThread], mute: Boolean): Boolean = {
    db.readWrite { implicit session =>
      getNonUserThreadOptWithSession(id) map { nonUserThread =>
        if (nonUserThread.muted != mute) {
          nonUserThreadRepo.setMuteState(nonUserThread.id.get, mute)
          true
        } else {
          false
        }
      }
    } map { stateChanged =>
      // TODO(martin) analytics for non users
      stateChanged
    } getOrElse (false)
  }

  def getChatter(userId: Id[User], urls: Seq[String]) = {
    implicit val timeout = Duration(3, "seconds")
    TimeoutFuture(Future.sequence(urls.map(u => shoebox.getNormalizedURIByURL(u).map(n => u -> n)))).recover {
      case ex: TimeoutException => Seq[(String, Option[NormalizedURI])]()
    }.map { res =>
      val urlMsgCount = db.readOnlyReplica { implicit session =>
        res.filter(_._2.isDefined).map {
          case (url, nuri) =>
            url -> userThreadRepo.getThreadIds(userId, Some(nuri.get.id.get))
        }
      }
      Map(urlMsgCount: _*)
    }
  }

  def sendMessageAction(
    title: Option[String],
    text: String,
    source: Option[MessageSource],
    userExtRecipients: Seq[ExternalId[User]],
    nonUserRecipients: Seq[BasicContact],
    validOrgRecipients: Seq[PublicId[Organization]],
    url: String,
    userId: Id[User],
    initContext: HeimdalContext): Future[(Message, Option[ElizaThreadInfo], Seq[MessageWithBasicUser])] = {
    val tStart = currentDateTime

    val userRecipientsFuture = shoebox.getUserIdsByExternalIds(userExtRecipients)
    val nonUserRecipientsFuture = constructNonUserRecipients(userId, nonUserRecipients)

    val orgIds = validOrgRecipients.map(o => Organization.decodePublicId(o)).filter(_.isSuccess).map(_.get)
    val moreContext = new HeimdalContextBuilder()
    val orgParticipantsFuture = Future.sequence(orgIds.map { oid =>
      shoebox.hasOrganizationMembership(oid, userId).flatMap {
        case true =>
          //ignoring case of multiple org ids in the same chat, just picking the last one
          moreContext += ("messagedWholeOrgId", oid.id)
          shoebox.getOrganizationMembers(oid)
        case false =>
          Future.successful(Set.empty[Id[User]])
      }
    }).map(_.flatten)

    val context = moreContext.addExistingContext(initContext).build

    val resFut = for {
      userRecipients <- userRecipientsFuture
      nonUserRecipients <- nonUserRecipientsFuture
      orgParticipants <- orgParticipantsFuture
      (thread, message) <- sendNewMessage(userId, userRecipients ++ orgParticipants, nonUserRecipients, url, title, text, source)(context)
      (messageThread, messagesWithBasicUser) <- basicMessageCommander.getThreadMessagesWithBasicUser(userId, thread)
      threadInfos <- buildThreadInfos(userId, Seq(thread), Some(url))
    } yield {
      val actions = userRecipients.map(id => (Left(id), "message")) ++ nonUserRecipients.collect {
        case NonUserEmailParticipant(address) => (Right(address), "message")
      }
      shoebox.addInteractions(userId, actions)

      val threadInfoOpt = threadInfos.headOption

      val tDiff = currentDateTime.getMillis - tStart.getMillis
      statsd.timing(s"messaging.newMessage", tDiff, ONE_IN_HUNDRED)
      (message, threadInfoOpt, messagesWithBasicUser)
    }
    resFut
  }

  def validateUsers(rawUsers: Seq[JsValue]): Seq[JsResult[ExternalId[User]]] = rawUsers.map(_.validate[ExternalId[User]])
  def validateEmailContacts(rawNonUsers: Seq[JsValue]): Seq[JsResult[BasicContact]] = rawNonUsers.map(_.validate[JsObject].map {
    case obj if (obj \ "kind").as[String] == "email" => (obj \ "email").as[BasicContact]
  })

  def parseRecipients(rawRecipients: Seq[JsValue]): (Seq[ExternalId[User]], Seq[BasicContact], Seq[PublicId[Organization]]) = {
    val (rawUsers, rawNonUsers, rawOrgs) = rawRecipients.foldRight((Seq.empty[ExternalId[User]], Seq.empty[BasicContact], Seq.empty[PublicId[Organization]])) { (recipient, acc) =>
      recipient.asOpt[JsString] match { // heuristic to determine if it's a user or an org
        case Some(JsString(id)) if id.startsWith("o") =>
          (acc._1, acc._2, acc._3 :+ PublicId(id))
        case Some(JsString(id)) if id.length == 36 =>
          (acc._1 ++ ExternalId.asOpt[User](id), acc._2, acc._3)
        case _ => // Starting in v XXXX `kind` is always sent (9/2/2015). Above can be removed after a good while.
          recipient.asOpt[JsObject].flatMap {
            case recip if (recip \ "kind").asOpt[String].exists(_ == "user") =>
              (recip \ "id").asOpt[ExternalId[User]].map { userExtId =>
                (acc._1 :+ userExtId, acc._2, acc._3)
              }
            case recip if (recip \ "kind").asOpt[String].exists(_ == "org") =>
              (recip \ "id").asOpt[PublicId[Organization]].map { orgPubId =>
                (acc._1, acc._2, acc._3 :+ orgPubId)
              }
            case recip if (recip \ "kind").asOpt[String].exists(_ == "email") =>
              (recip \ "email").asOpt[BasicContact].map { contact => // this is weird as heck
                (acc._1, acc._2 :+ contact, acc._3)
              }
          }.getOrElse {
            log.warn(s"[validateRecipients] Could not determine what ${recipient.toString} is supposed to be.")
            (acc._1, acc._2, acc._3)
          }
      }
    }

    (rawUsers, rawNonUsers, rawOrgs)
  }

  private def checkEmailParticipantRateLimits(user: Id[User], thread: MessageThread, nonUsers: Seq[NonUserParticipant])(implicit session: RSession): Unit = {

    // Check rate limit for this discussion
    val distinctEmailRecipients = nonUsers.collect { case emailParticipant: NonUserEmailParticipant => emailParticipant.address }.toSet
    val existingEmailParticipants = thread.participants.map(_.allNonUsers).getOrElse(Set.empty).collect { case emailParticipant: NonUserEmailParticipant => emailParticipant.address }

    val totalEmailParticipants = (existingEmailParticipants ++ distinctEmailRecipients).size
    val newEmailParticipants = totalEmailParticipants - existingEmailParticipants.size

    if (totalEmailParticipants > MessagingCommander.MAX_NON_USER_PARTICIPANTS_PER_THREAD) {
      throw new ExternalMessagingRateLimitException(MessagingCommander.maxEmailRecipientsPerThreadErrorMessage(user, nonUsers.size))
    }

    if (totalEmailParticipants >= MessagingCommander.WARNING_NON_USER_PARTICIPANTS_PER_THREAD && newEmailParticipants > 0) {
      val warning = s"Discussion ${thread.id.get} ${thread.uriId.map("on uri " + _).getOrElse("")} has $totalEmailParticipants non user participants after user $user reached to $newEmailParticipants new people."
      airbrake.notify(new ExternalMessagingRateLimitException(warning))
    }

    // Check rate limit for this user
    val since = clock.now.minus(MessagingCommander.RECENT_NON_USER_RECIPIENTS_WINDOW.toMillis)
    val recentEmailRecipients = nonUserThreadRepo.getRecentRecipientsByUser(user, since).keySet.map(_.address)
    val totalRecentEmailRecipients = (recentEmailRecipients ++ distinctEmailRecipients).size
    val newRecentEmailRecipients = totalRecentEmailRecipients - recentEmailRecipients.size

    if (totalRecentEmailRecipients > MessagingCommander.MAX_RECENT_NON_USER_RECIPIENTS) {
      throw new ExternalMessagingRateLimitException(MessagingCommander.maxRecentEmailRecipientsErrorMessage)
    }

    if (totalRecentEmailRecipients > MessagingCommander.WARNING_RECENT_NON_USER_RECIPIENTS && newRecentEmailRecipients > 0) {
      val warning = s"User $user has reached to $totalRecentEmailRecipients distinct email recipients in the past ${MessagingCommander.RECENT_NON_USER_RECIPIENTS_WINDOW}"
      throw new ExternalMessagingRateLimitException(warning)
    }
  }
}

class ExternalMessagingRateLimitException(message: String) extends Exception(message)
