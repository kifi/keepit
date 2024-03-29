package com.keepit.eliza.commanders

import com.google.inject.{ ImplementedBy, Inject }
import com.keepit.abook.ABookServiceClient
import com.keepit.common.core.eitherExtensionOps
import com.keepit.common.akka.SafeFuture
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.mail.BasicContact
import com.keepit.common.net.URI
import com.keepit.common.store.S3ImageConfig
import com.keepit.common.time._
import com.keepit.common.util.{ DeltaSet, Ord }
import com.keepit.discussion.MessageSource
import com.keepit.eliza.ElizaServiceClient
import com.keepit.eliza.model.SystemMessageData._
import com.keepit.eliza.model._
import com.keepit.heimdal.{ HeimdalContext, HeimdalContextBuilder }
import com.keepit.model.BasicKeepEvent.BasicKeepEventId
import com.keepit.model._
import com.keepit.notify.model.Recipient
import com.keepit.notify.model.event.LibraryNewKeep
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.social.{ BasicAuthor, BasicUser, BasicUserLikeEntity, NonUserKinds }
import org.joda.time.DateTime
import play.api.libs.json._

import scala.concurrent.duration._
import scala.concurrent.{ Await, ExecutionContext, Future }
import scala.util.{ Failure, Random, Success }

case class NotAuthorizedException(msg: String) extends java.lang.Throwable(msg)

object MessagingCommander {
  val THREAD_PAGE_SIZE = 20

  val MAX_RECENT_NON_USER_RECIPIENTS = 100
  val WARNING_RECENT_NON_USER_RECIPIENTS = 75
  val RECENT_NON_USER_RECIPIENTS_WINDOW = 24 hours
  val MAX_NON_USER_PARTICIPANTS_PER_THREAD = 30
  val WARNING_NON_USER_PARTICIPANTS_PER_THREAD = 20

  val maxRecentEmailRecipientsErrorMessage = s"You are allowed $MAX_NON_USER_PARTICIPANTS_PER_THREAD email recipients per discussion."

  def maxEmailRecipientsPerThreadErrorMessage(user: Id[User], emailCount: Int) = s"You (user #$user) have hit the limit on the number of emails ($emailCount) you are able to send through Kifi."
}

@ImplementedBy(classOf[MessagingCommanderImpl])
trait MessagingCommander {
  // todo: For each method here, remove if no one's calling it externally, and set as private in the implementation
  def getThreadInfos(userId: Id[User], url: String): Future[(String, Seq[ElizaThreadInfo])]
  def keepAttribution(userId: Id[User], uriId: Id[NormalizedURI]): Future[Set[Id[User]]]
  def sendMessageWithNonUserThread(nut: NonUserThread, messageText: String, source: Option[MessageSource], urlOpt: Option[URI])(implicit time: CrossServiceTime, context: HeimdalContext): (MessageThread, ElizaMessage)
  def sendMessageWithUserThread(userThread: UserThread, messageText: String, source: Option[MessageSource], urlOpt: Option[URI])(implicit time: CrossServiceTime, context: HeimdalContext): (MessageThread, ElizaMessage)
  def sendMessage(from: Id[User], thread: MessageThread, messageText: String, source: Option[MessageSource], urlOpt: Option[URI])(implicit time: CrossServiceTime, context: HeimdalContext): (MessageThread, ElizaMessage)
  def sendMessageAction(title: Option[String], text: String, source: Option[MessageSource], userExtRecipients: Seq[ExternalId[User]], nonUserRecipients: Seq[BasicContact],
    url: String, userId: Id[User], initContext: HeimdalContext)(implicit time: CrossServiceTime): Future[(ElizaMessage, ElizaThreadInfo, Seq[MessageWithBasicUser])]
  def getNonUserThreadOpt(id: Id[NonUserThread]): Option[NonUserThread]
  def getNonUserThreadOptByAccessToken(token: ThreadAccessToken): Option[NonUserThread]
  def getUserThreadOptByAccessToken(token: ThreadAccessToken): Option[UserThread]

  def setRead(userId: Id[User], messageId: Id[ElizaMessage])(implicit context: HeimdalContext): Unit
  def setUserThreadRead(userId: Id[User], keepId: Id[Keep])(implicit context: HeimdalContext): Unit
  def setUnread(userId: Id[User], messageId: Id[ElizaMessage]): Unit
  def setUserThreadUnread(userId: Id[User], keepId: Id[Keep]): Unit
  def setLastSeen(userId: Id[User], keepId: Id[Keep], timestampOpt: Option[DateTime] = None): Unit
  def setLastSeen(userId: Id[User], messageId: Id[ElizaMessage]): Unit
  def getUnreadUnmutedThreadCount(userId: Id[User]): Int
  def muteThreadForNonUser(id: Id[NonUserThread]): Boolean
  def unmuteThreadForNonUser(id: Id[NonUserThread]): Boolean
  def setUserThreadMuteState(userId: Id[User], keepId: Id[Keep], mute: Boolean)(implicit context: HeimdalContext): Boolean
  def setNonUserThreadMuteState(id: Id[NonUserThread], mute: Boolean): Boolean
  def modifyThreadParticipants(requester: Id[User], keepId: Id[Keep], users: DeltaSet[Id[User]], contacts: DeltaSet[BasicContact], source: Option[KeepEventSource])(implicit context: HeimdalContext): Future[(MessageThread, KeepRecipientsDiff)]

  def validateUsers(rawUsers: Seq[JsValue]): Seq[JsResult[ExternalId[User]]]
  def validateEmailContacts(rawNonUsers: Seq[JsValue]): Seq[JsResult[BasicContact]]
  def parseRecipients(rawRecipients: Seq[JsValue]): (Seq[ExternalId[User]], Seq[BasicContact])
}

class MessagingCommanderImpl @Inject() (
    eliza: ElizaServiceClient,
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
    basicMessageCommander: MessageFetchingCommander,
    notificationDeliveryCommander: NotificationDeliveryCommander,
    messageSearchHistoryRepo: MessageSearchHistoryRepo,
    // these notif* classes are only needed to kill NewKeep notifications once a message thread exists for that keep
    notifRepo: NotificationRepo,
    notifItemRepo: NotificationItemRepo,
    notifCommander: NotificationCommander,
    implicit val executionContext: ExecutionContext,
    implicit val publicIdConfig: PublicIdConfiguration,
    implicit val imageConfig: S3ImageConfig) extends MessagingCommander with Logging {

  private def buildThreadInfos(userId: Id[User], threads: Seq[MessageThread], requestUrl: String): Future[Seq[ElizaThreadInfo]] = {
    val allInvolvedUsers = threads.flatMap(_.participants.allUsers)
    val userId2BasicUserF = shoebox.getBasicUsers(allInvolvedUsers.toSeq)
    val discussionKeepsByKeepIdF = shoebox.getDiscussionKeepsByIds(userId, threads.map(_.keepId).toSet)
    val messagesByThread: Map[Id[MessageThread], Seq[ElizaMessage]] = threads.map { thread =>
      (thread.id.get, basicMessageCommander.getMessagesByKeepId(thread.keepId))
    }.toMap
    val userThreads: Map[Id[MessageThread], UserThread] = db.readOnlyMaster { implicit session =>
      threads.map { thread => thread.id.get -> userThreadRepo.getUserThread(userId, thread.keepId).get }
    }.toMap

    for {
      userId2BasicUser <- userId2BasicUserF
      discussionKeepsByKeepId <- discussionKeepsByKeepIdF
    } yield {
      threads.map { thread =>

        val lastMessageOpt = messagesByThread(thread.id.get).collectFirst { case m if m.from.asUser.isDefined => m }
        if (lastMessageOpt.isEmpty) log.error(s"EMPTY THREAD! thread_id: ${thread.id.get} request_url: $requestUrl user: $userId")
        val lastMessage = lastMessageOpt.get

        val messageTimes = messagesByThread(thread.id.get).take(10).map { message =>
          (message.pubId, message.createdAt)
        }.toMap

        val nonUsers = thread.participants.allNonUsers
          .map(nu => BasicUserLikeEntity(EmailParticipant.toBasicNonUser(nu))).toSeq

        val basicUsers = thread.participants.allUsers
          .map(u => BasicUserLikeEntity(userId2BasicUser(u))).toSeq

        ElizaThreadInfo(
          keepId = thread.pubKeepId,
          participants = basicUsers ++ nonUsers,
          digest = lastMessage.messageText,
          lastAuthor = userId2BasicUser(lastMessage.from.asUser.get).externalId,
          messageCount = messagesByThread(thread.id.get).length,
          messageTimes = messageTimes,
          createdAt = thread.createdAt,
          lastCommentedAt = lastMessage.createdAt,
          lastMessageRead = userThreads(thread.id.get).lastSeen,
          nUrl = Some(thread.nUrl),
          url = requestUrl,
          muted = userThreads(thread.id.get).muted,
          keep = discussionKeepsByKeepId.get(thread.keepId))
      }
    }
  }

  def getThreadInfos(userId: Id[User], url: String): Future[(String, Seq[ElizaThreadInfo])] = {
    new SafeFuture(shoebox.getNormalizedUriByUrlOrPrenormalize(url).flatMap {
      case Left(nUri) =>
        shoebox.getPersonalKeepRecipientsOnUris(userId, Set(nUri.id.get), excludeAccess = Some(LibraryAccess.READ_ONLY)).flatMap { keepsByUriId =>
          val keepIds = keepsByUriId.getOrElse(nUri.id.get, Set.empty).map(_.id)
          val threads = db.readOnlyReplica { implicit session =>
            val threadsByKeepId = threadRepo.getByKeepIds(keepIds)
            keepIds.flatMap(threadsByKeepId.get)
          }
          buildThreadInfos(userId, threads.toSeq, url).map { unsortedInfos =>
            val infos = unsortedInfos sortWith { (a, b) =>
              a.lastCommentedAt.compareTo(b.lastCommentedAt) < 0
            }
            (nUri.url, infos)
          }
        }
      case Right(prenormalizedUrl) =>
        Future.successful((prenormalizedUrl, Seq[ElizaThreadInfo]()))
    })
  }

  def keepAttribution(userId: Id[User], uriId: Id[NormalizedURI]): Future[Set[Id[User]]] = {
    shoebox.getPersonalKeepRecipientsOnUris(userId, Set(uriId)).map { keepByUriId =>
      val keepIds = keepByUriId.getOrElse(uriId, Set.empty).map(_.id)
      val otherStarters = db.readOnlyReplica { implicit session =>
        keepIds.flatMap { keepId =>
          userThreadRepo.getUserThread(userId, keepId).collect {
            case ut if ut.lastSeen.exists(dt => dt.plusDays(3).isAfterNow) && ut.startedBy != userId => ut.startedBy
          }
        }
      }
      log.info(s"[keepAttribution($userId,$uriId)] keeps=$keepIds otherStarters=$otherStarters")
      otherStarters
    }
  }

  private def constructEmailRecipients(userId: Id[User], nonUsers: Seq[BasicContact]): Future[Seq[EmailParticipant]] = {
    abookServiceClient.internKifiContacts(userId, nonUsers: _*).map { richContacts =>
      richContacts.map(richContact => EmailParticipant(richContact.email))
    }
  }

  private def updateMessageSearchHistoryWithEmailAddresses(userId: Id[User], nups: Seq[EmailParticipant]) = {
    SafeFuture("adding email address to message search history") {
      db.readWrite { implicit session =>
        val history = messageSearchHistoryRepo.getOrCreate(userId)
        if (!history.optOut) {
          messageSearchHistoryRepo.save(history.withNewEmails(nups.filter(_.kind == NonUserKinds.email).map(_.identifier)))
        }
      }
    }
  }

  private def getOrCreateThread(from: Id[User], userParticipants: Seq[Id[User]], nonUserRecipients: Seq[EmailParticipant], url: String, nUriId: Id[NormalizedURI], nUrl: String, titleOpt: Option[String], source: Option[KeepSource]): Future[(MessageThread, Boolean)] = {
    val mtParticipants = MessageThreadParticipants.fromSets(userParticipants.toSet, nonUserRecipients.toSet)
    val matches = db.readOnlyMaster { implicit s =>
      threadRepo.getByUriAndParticipants(nUriId, mtParticipants)
    }
    matches.headOption match {
      case Some(mt) => Future.successful(mt, false)
      case None =>
        shoebox.internKeep(from, userParticipants.toSet, nonUserRecipients.map { case EmailParticipant(address) => address }.toSet, nUriId, url, titleOpt, None, source).map { csKeep =>
          db.readWrite { implicit s =>
            val thread = threadRepo.save(MessageThread(
              uriId = nUriId,
              url = url,
              nUrl = nUrl,
              pageTitle = titleOpt,
              startedBy = from,
              participants = mtParticipants,
              keepId = csKeep.id,
              numMessages = 0
            ))
            (thread, true)
          }
        }
    }
  }
  def sendNewMessage(from: Id[User], userRecipients: Seq[Id[User]], nonUserRecipients: Seq[EmailParticipant], url: String, titleOpt: Option[String], messageText: String, source: Option[MessageSource])(implicit time: CrossServiceTime, context: HeimdalContext): Future[(MessageThread, ElizaMessage)] = {
    updateMessageSearchHistoryWithEmailAddresses(from, nonUserRecipients)
    val userParticipants = (from +: userRecipients).distinct

    val uriFetch = URI.parse(url) match {
      case Success(parsed) =>
        shoebox.internNormalizedURI(parsed.toString(), contentWanted = true).map(n => (parsed, n.id.get, n.url, n.title))
      case Failure(e) => throw new Exception(s"can't send message for bad URL: [$url] from $from with title $titleOpt and source $source")
    }

    val keepSource = source.flatMap(KeepSource.fromMessageSource)

    for {
      ((uri, nUriId, nUrl, nTitleOpt)) <- uriFetch
      (thread, isNew) <- getOrCreateThread(from, userParticipants, nonUserRecipients, url, nUriId, nUrl, titleOpt.orElse(nTitleOpt), keepSource)
    } yield {
      if (isNew) {
        db.readWrite { implicit s =>
          checkEmailParticipantRateLimits(from, thread, nonUserRecipients.toSet)
          nonUserRecipients.foreach { nonUser =>
            nonUserThreadRepo.save(NonUserThread(
              createdBy = from,
              participant = nonUser,
              keepId = thread.keepId,
              uriId = Some(nUriId),
              notifiedCount = 0,
              lastNotifiedAt = None,
              threadUpdatedByOtherAt = Some(thread.createdAt),
              muted = false
            ))
          }
          userParticipants.foreach { userId =>
            userThreadRepo.intern(UserThread.forMessageThread(thread)(userId))
          }
        }
      } else {
        log.info(s"Not actually a new thread. Merging.")
      }

      //this is code for a special status message used in the client to do the email preview
      if (nonUserRecipients.nonEmpty) {
        db.readWrite { implicit session =>
          messageRepo.save(ElizaMessage(
            keepId = thread.keepId,
            commentIndexOnKeep = None,
            from = MessageSender.System,
            messageText = "",
            source = source,
            auxData = Some(StartWithEmails(from, userRecipients, nonUserRecipients)),
            sentOnUrl = None,
            sentOnUriId = None
          ))
        }
      }
      sendMessage(MessageSender.User(from), thread, messageText, source, Some(uri), Some(nUriId), Some(isNew))
    }
  }

  def sendMessageWithNonUserThread(nut: NonUserThread, messageText: String, source: Option[MessageSource], urlOpt: Option[URI])(implicit time: CrossServiceTime, context: HeimdalContext): (MessageThread, ElizaMessage) = {
    log.info(s"Sending message from non-user with id ${nut.id} to keep ${nut.keepId}")
    val thread = db.readOnlyMaster { implicit session => threadRepo.getByKeepId(nut.keepId).get }
    sendMessage(MessageSender.NonUser(nut.participant), thread, messageText, source, urlOpt)
  }

  def sendMessageWithUserThread(userThread: UserThread, messageText: String, source: Option[MessageSource], urlOpt: Option[URI])(implicit time: CrossServiceTime, context: HeimdalContext): (MessageThread, ElizaMessage) = {
    log.info(s"Sending message from user with id ${userThread.user} to keep ${userThread.keepId}")
    val thread = db.readOnlyMaster { implicit session => threadRepo.getByKeepId(userThread.keepId).get }
    sendMessage(MessageSender.User(userThread.user), thread, messageText, source, urlOpt)
  }

  def sendMessage(from: Id[User], thread: MessageThread, messageText: String, source: Option[MessageSource], urlOpt: Option[URI])(implicit time: CrossServiceTime, context: HeimdalContext): (MessageThread, ElizaMessage) = {
    sendMessage(MessageSender.User(from), thread, messageText, source, urlOpt)
  }

  private def sendMessage(from: MessageSender, initialThread: MessageThread, messageText: String, source: Option[MessageSource], urlOpt: Option[URI], nUriIdOpt: Option[Id[NormalizedURI]] = None, isNew: Option[Boolean] = None)(implicit time: CrossServiceTime, context: HeimdalContext): (MessageThread, ElizaMessage) = {
    from match {
      case MessageSender.User(id) =>
        if (!initialThread.participants.contains(id)) throw NotAuthorizedException(s"User $id not authorized to send message on thread ${initialThread.id.get}")
      case MessageSender.NonUser(nup) =>
        if (!initialThread.participants.contains(nup)) throw NotAuthorizedException(s"Non-User $nup not authorized to send message on thread ${initialThread.id.get}")
      case MessageSender.System =>
        throw NotAuthorizedException("Wrong code path for system Messages.")
    }

    log.info(s"Sending message from $from on keep ${initialThread.keepId}, csTime = ${time.time} so createdAt = ${Ord.max(clock.now, time.time)}")
    val (message, thread) = db.readWrite { implicit session =>
      val msg = messageRepo.save(ElizaMessage(
        createdAt = Ord.max(clock.now, time.time),
        keepId = initialThread.keepId,
        commentIndexOnKeep = Some(initialThread.numMessages),
        from = from,
        messageText = messageText,
        source = source,
        sentOnUrl = urlOpt.flatMap(_.raw).orElse(Some(initialThread.url)),
        sentOnUriId = Some(initialThread.uriId)
      ))
      val thread = threadRepo.save(initialThread.withNumMessages(initialThread.numMessages + 1))
      userThreadRepo.registerMessage(msg)
      (msg, thread)
    }

    SafeFuture {
      db.readOnlyMaster { implicit session => messageRepo.refreshCache(thread.keepId) }
      shoebox.registerMessageOnKeep(thread.keepId, ElizaMessage.toCrossServiceMessage(message))
      from.asUser.foreach(user => shoebox.editRecipientsOnKeep(editorId = user, keepId = thread.keepId, diff = KeepRecipientsDiff.addUser(user)))
    }

    val participantSet = thread.participants.allUsers
    val nonUserParticipantsSet = thread.participants.allNonUsers
    val id2BasicUser = Await.result(shoebox.getBasicUsers(participantSet.toSeq), 1.seconds) // todo: remove await
    val basicNonUserParticipants = nonUserParticipantsSet.map(EmailParticipant.toBasicNonUser)
      .map(nu => BasicUserLikeEntity(nu))

    val sender = message.from match {
      case MessageSender.User(id) => Some(BasicUserLikeEntity(id2BasicUser(id)))
      case MessageSender.NonUser(nup) => Some(BasicUserLikeEntity(EmailParticipant.toBasicNonUser(nup)))
      case _ => None
    }

    val messageWithBasicUser = MessageWithBasicUser(
      BasicKeepEventId.fromPubMsg(message.pubId),
      message.createdAt,
      message.messageText,
      source,
      None,
      message.sentOnUrl.getOrElse(""),
      thread.nUrl,
      message.from match {
        case MessageSender.User(id) => BasicUserLikeEntity(id2BasicUser(id))
        case MessageSender.NonUser(nup) => BasicUserLikeEntity(EmailParticipant.toBasicNonUser(nup))
        case _ => BasicUserLikeEntity(BasicUser(ExternalId[User]("42424242-4242-4242-4242-000000000001"), "Kifi", "", "0.jpg", Username("sssss")))
      },
      participantSet.toSeq.map(u => BasicUserLikeEntity(id2BasicUser(u))) ++ basicNonUserParticipants
    )

    val author = message.from match {
      case MessageSender.User(id) => BasicAuthor.fromUser(id2BasicUser(id))
      case MessageSender.NonUser(nup) => BasicAuthor.fromNonUser(EmailParticipant.toBasicNonUser(nup))
      case _ => BasicAuthor.Fake
    }

    val event = BasicKeepEvent.generateCommentEvent(message.pubId, author, message.messageText, message.createdAt, message.source)

    // send message through websockets immediately
    thread.participants.allUsers.foreach { user =>
      notificationDeliveryCommander.notifyMessage(user, message.pubKeepId, messageWithBasicUser)
      notificationDeliveryCommander.sendKeepEvent(user, message.pubKeepId, event)
    }
    // Anyone who got this new notification might have a NewKeep notification that is going to be
    // passively replaced by this message thread. The client will then have no way of ever marking or
    // displaying that notification, so they cannot mark it as read (resulting in a persistent
    // incorrect unread-notification count). Try and find this notification and murder it.
    SafeFuture {
      val newKeepNotifsToMarkAsRead = db.readOnlyMaster { implicit s =>
        thread.participants.allUsers.flatMap { user =>
          notifRepo.getAllUnreadByRecipientAndKind(Recipient.fromUser(user), LibraryNewKeep).find { newKeepNotif =>
            notifItemRepo.getAllForNotification(newKeepNotif.id.get) match {
              case Seq(oneItem) => oneItem.event match {
                case lnk: LibraryNewKeep => lnk.keepId == thread.keepId
                case _ => false
              }
              case _ => false
            }
          }
        }
      }
      newKeepNotifsToMarkAsRead.foreach { notif => notifCommander.setNotificationUnreadTo(notif.id.get, unread = false) }
    }

    // For everyone except the sender, mark their thread as unread
    db.readWrite { implicit s =>
      (thread.participants.allUsers -- from.asUser).foreach { user =>
        userThreadRepo.markUnread(user, message.keepId)
      }
    }

    // update user thread of the sender
    from.asUser.foreach { sender =>
      setLastSeen(sender, message.keepId, Some(message.createdAt))
      db.readWrite { implicit session =>
        userThreadRepo.setLastActive(sender, message.keepId, message.createdAt)
        userThreadRepo.markRead(sender, message)
      }
    }

    // update user threads of user recipients - this somehow depends on the sender's user thread update above
    val threadActivity: Seq[UserThreadActivity] = db.readOnlyMaster { implicit session =>
      userThreadRepo.getThreadActivity(message.keepId).sortBy { uta =>
        (-uta.lastActive.getOrElse(START_OF_TIME).getMillis, uta.id.id)
      }
    }

    thread.participants.allUsers.foreach { userId =>
      notificationDeliveryCommander.sendNotificationForMessage(userId, message, thread, sender, threadActivity)
      notificationDeliveryCommander.sendPushNotificationForMessage(userId, message, sender, threadActivity)
    }

    // update non user threads of non user recipients
    notificationDeliveryCommander.updateEmailParticipantThreads(thread, message)
    if (isNew.contains(true)) { notificationDeliveryCommander.notifyEmailParticipants(thread) }

    //async update normalized url id so as not to block on that (the shoebox call yields a future)
    urlOpt.foreach { url =>
      nUriIdOpt.map(Future.successful).getOrElse {
        shoebox.internNormalizedURI(url.toString(), contentWanted = true).map(_.id.get) //Note, this also needs to include canonical/og when we have detached threads
      }.foreach { nUriId =>
        db.readWrite { implicit s => messageRepo.updateUriId(message, nUriId) }
      }
    }
    messagingAnalytics.sentMessage(message, thread, isNew, context)

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

  def modifyThreadParticipants(requester: Id[User], keepId: Id[Keep], users: DeltaSet[Id[User]], contacts: DeltaSet[BasicContact], source: Option[KeepEventSource])(implicit context: HeimdalContext): Future[(MessageThread, KeepRecipientsDiff)] = {
    abookServiceClient.internKifiContacts(requester, contacts.all.toSeq: _*)

    val (thread, diff) = db.readWrite { implicit session =>
      val oldThread = threadRepo.getByKeepId(keepId).get
      val actualUsers = DeltaSet.trimForSet(users, oldThread.participants.allUsers)
      val actualEmails = DeltaSet.trimForSet(contacts.map(_.email), oldThread.participants.allEmails)
      checkEmailParticipantRateLimits(requester, oldThread, contacts.added.map(c => EmailParticipant(c.email)))
      if (!oldThread.participants.contains(requester)) {
        throw NotAuthorizedException(s"User $requester not authorized to add participants to keep $keepId")
      }
      val thread = threadRepo.save(oldThread.withParticipants(oldThread.participants.diffed(actualUsers, actualEmails)))
      actualUsers.added.foreach(uId => userThreadRepo.intern(UserThread.forMessageThread(thread)(user = uId)))
      actualUsers.removed.foreach(uId => userThreadRepo.getUserThread(uId, keepId).foreach(userThreadRepo.deactivate))
      actualEmails.added.foreach(email => nonUserThreadRepo.intern(NonUserThread.forMessageThread(thread)(email)))
      actualEmails.removed.foreach(email => nonUserThreadRepo.getByKeepAndEmail(keepId, email).foreach(nonUserThreadRepo.deactivate))
      val diff = KeepRecipientsDiff(actualUsers, libraries = DeltaSet.empty, actualEmails)
      messageRepo.refreshCache(keepId)

      (thread, diff)
    }
    messagingAnalytics.addedParticipantsToConversation(requester, diff.users.added.toSeq, diff.emails.added.toSeq.map(EmailParticipant(_)), thread, source, context)
    Future.successful((thread, diff))
  }

  def setRead(userId: Id[User], messageId: Id[ElizaMessage])(implicit context: HeimdalContext): Unit = {
    val (message: ElizaMessage, thread: MessageThread) = db.readOnlyMaster { implicit session =>
      val message = messageRepo.get(messageId)
      (message, threadRepo.getByKeepId(message.keepId).get)
    }
    db.readWrite(attempts = 2) { implicit session =>
      userThreadRepo.markRead(userId, message)
    }
    messagingAnalytics.clearedNotification(userId, Right(message.pubKeepId), Right(message.pubId), context)

    notificationDeliveryCommander.notifyRead(userId, message.keepId, Some(message.id.get), thread.nUrl, message.createdAt)
  }

  def setUserThreadRead(userId: Id[User], keepId: Id[Keep])(implicit context: HeimdalContext): Unit = {
    val changed = db.readWrite(attempts = 2)(implicit session => userThreadRepo.markUserThreadRead(userId, keepId))
    if (changed) {
      val thread = db.readOnlyMaster(implicit s => threadRepo.getByKeepId(keepId)).get
      notificationDeliveryCommander.notifyRead(userId, keepId, messageIdOpt = None, thread.nUrl, clock.now())
    }
  }

  def setUnread(userId: Id[User], messageId: Id[ElizaMessage]): Unit = {
    val (message: ElizaMessage, thread: MessageThread) = db.readOnlyMaster { implicit session =>
      val message = messageRepo.get(messageId)
      (message, threadRepo.getByKeepId(message.keepId).get)
    }
    val changed: Boolean = db.readWrite(attempts = 2) { implicit session =>
      userThreadRepo.markUnread(userId, message.keepId)
    }
    if (changed) {
      notificationDeliveryCommander.notifyUnread(userId, message.keepId, Some(message.id.get), thread.nUrl, message.createdAt)
    }
  }

  def setUserThreadUnread(userId: Id[User], keepId: Id[Keep]): Unit = {
    val changed: Boolean = db.readWrite(attempts = 2) { implicit session =>
      userThreadRepo.markUnread(userId, keepId)
    }
    if (changed) {
      val thread = db.readOnlyMaster(implicit s => threadRepo.getByKeepId(keepId)).get
      notificationDeliveryCommander.notifyUnread(userId, keepId, messageIdOpt = None, thread.nUrl, clock.now())
    }
  }

  def setLastSeen(userId: Id[User], keepId: Id[Keep], timestampOpt: Option[DateTime] = None): Unit = {
    db.readWrite { implicit session =>
      userThreadRepo.setLastSeen(userId, keepId, timestampOpt.getOrElse(clock.now))
    }
  }

  def setLastSeen(userId: Id[User], messageId: Id[ElizaMessage]): Unit = {
    val message: ElizaMessage = db.readOnlyMaster { implicit session =>
      messageRepo.get(messageId)
    }
    setLastSeen(userId, message.keepId, Some(message.createdAt))
  }

  def getUnreadUnmutedThreadCount(userId: Id[User]): Int = {
    db.readOnlyReplica { implicit session => userThreadRepo.getUnreadThreadCounts(userId).unmuted }
  }

  def muteThreadForNonUser(id: Id[NonUserThread]): Boolean = setNonUserThreadMuteState(id, mute = true)
  def unmuteThreadForNonUser(id: Id[NonUserThread]): Boolean = setNonUserThreadMuteState(id, mute = false)

  def setUserThreadMuteState(userId: Id[User], keepId: Id[Keep], mute: Boolean)(implicit context: HeimdalContext): Boolean = {
    val (stateChanged) = db.readWrite { implicit session =>
      threadRepo.getByKeepId(keepId).exists { thread =>
        userThreadRepo.getUserThread(userId, thread.keepId) match {
          case Some(ut) if ut.muted != mute =>
            userThreadRepo.setMuteState(ut.id.get, mute)
          case _ => false
        }
      }
    }
    if (stateChanged) {
      notificationDeliveryCommander.notifyUserAboutMuteChange(userId, Keep.publicId(keepId), mute)
      messagingAnalytics.changedMute(userId, keepId, mute, context)
    }
    stateChanged
  }

  def setNonUserThreadMuteState(id: Id[NonUserThread], mute: Boolean): Boolean = {
    db.readWrite { implicit session =>
      getNonUserThreadOptWithSession(id).collect {
        case nut if nut.muted != mute =>
          nonUserThreadRepo.setMuteState(nut.id.get, mute)
          true
      }
    }.contains(true)
  }

  def sendMessageAction(
    title: Option[String],
    text: String,
    source: Option[MessageSource],
    userExtRecipients: Seq[ExternalId[User]],
    nonUserRecipients: Seq[BasicContact],
    url: String,
    userId: Id[User],
    initContext: HeimdalContext)(implicit time: CrossServiceTime): Future[(ElizaMessage, ElizaThreadInfo, Seq[MessageWithBasicUser])] = {
    val tStart = currentDateTime

    val userRecipientsFuture = shoebox.getUserIdsByExternalIds(userExtRecipients.toSet).map(_.values.toSeq)
    val nonUserRecipientsFuture = constructEmailRecipients(userId, nonUserRecipients)

    val moreContext = new HeimdalContextBuilder()
    implicit val context = moreContext.addExistingContext(initContext).build

    val resFut = for {
      userRecipients <- userRecipientsFuture
      nonUserRecipients <- nonUserRecipientsFuture
      (thread, message) <- sendNewMessage(userId, userRecipients, nonUserRecipients, url, title, text, source)
      messagesWithBasicUser <- basicMessageCommander.getThreadMessagesWithBasicUser(thread)
      Seq(threadInfo) <- buildThreadInfos(userId, Seq(thread), url)
    } yield {
      val actions = userRecipients.map(id => (Left(id), "message")) ++ nonUserRecipients.map {
        case EmailParticipant(address) => (Right(address), "message")
      }
      shoebox.addInteractions(userId, actions)

      val tDiff = currentDateTime.getMillis - tStart.getMillis
      statsd.timing(s"messaging.newMessage", tDiff, ONE_IN_HUNDRED)
      (message, threadInfo, messagesWithBasicUser)
    }
    resFut
  }

  def validateUsers(rawUsers: Seq[JsValue]): Seq[JsResult[ExternalId[User]]] = rawUsers.map(_.validate[ExternalId[User]])
  def validateEmailContacts(rawNonUsers: Seq[JsValue]): Seq[JsResult[BasicContact]] = rawNonUsers.map(_.validate[JsObject].map {
    case obj if (obj \ "kind").as[String] == "email" => (obj \ "email").as[BasicContact]
  })

  def parseRecipients(rawRecipients: Seq[JsValue]): (Seq[ExternalId[User]], Seq[BasicContact]) = {
    rawRecipients.flatMap {
      case JsString(id) if id.length == 36 => ExternalId.asOpt[User](id).map(Left(_))
      case recip: JsObject if (recip \ "kind").asOpt[String].contains("user") => (recip \ "id").asOpt[ExternalId[User]].map(Left(_))
      case recip: JsObject if (recip \ "kind").asOpt[String].contains("email") => (recip \ "email").asOpt[BasicContact].map(Right(_))
      case unknown =>
        log.warn(s"[validateRecipients] Could not determine what ${Json.stringify(unknown)} is supposed to be.")
        None
    }.partitionEithers
  }

  private def checkEmailParticipantRateLimits(user: Id[User], thread: MessageThread, nonUsers: Set[EmailParticipant])(implicit session: RSession): Unit = {

    // Check rate limit for this discussion
    val distinctEmailRecipients = nonUsers.map(_.address)
    val existingEmailParticipants = thread.participants.allEmails

    val totalEmailParticipants = (existingEmailParticipants ++ distinctEmailRecipients).size
    val newEmailParticipants = totalEmailParticipants - existingEmailParticipants.size

    if (totalEmailParticipants > MessagingCommander.MAX_NON_USER_PARTICIPANTS_PER_THREAD) {
      throw new ExternalMessagingRateLimitException(MessagingCommander.maxEmailRecipientsPerThreadErrorMessage(user, nonUsers.size))
    }

    if (totalEmailParticipants >= MessagingCommander.WARNING_NON_USER_PARTICIPANTS_PER_THREAD && newEmailParticipants > 0) {
      val warning = s"Keep ${thread.keepId} on uri ${thread.uriId} has $totalEmailParticipants non user participants after user $user reached to $newEmailParticipants new people."
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
