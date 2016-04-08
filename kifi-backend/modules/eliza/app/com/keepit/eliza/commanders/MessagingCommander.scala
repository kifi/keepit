package com.keepit.eliza.commanders

import java.util.concurrent.TimeoutException

import com.google.inject.{ ImplementedBy, Inject }
import com.keepit.abook.ABookServiceClient
import com.keepit.common.akka.{ SafeFuture, TimeoutFuture }
import com.keepit.common.core.anyExtensionOps
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
import com.keepit.common.util.DeltaSet
import com.keepit.discussion.MessageSource
import com.keepit.eliza.model._
import com.keepit.eliza.model.SystemMessageData._
import com.keepit.heimdal.{ HeimdalContext, HeimdalContextBuilder }
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
  val maxRecentEmailRecipientsErrorMessage = s"You are allowed ${MessagingCommander.MAX_NON_USER_PARTICIPANTS_PER_THREAD} email recipients per discussion."

  val MAX_NON_USER_PARTICIPANTS_PER_THREAD = 30
  val WARNING_NON_USER_PARTICIPANTS_PER_THREAD = 20

  def maxEmailRecipientsPerThreadErrorMessage(user: Id[User], emailCount: Int) = s"You (user #$user) have hit the limit on the number of emails ($emailCount) you are able to send through Kifi."
}

@ImplementedBy(classOf[MessagingCommanderImpl])
trait MessagingCommander {
  // todo: For each method here, remove if no one's calling it externally, and set as private in the implementation
  def getThreadInfos(userId: Id[User], url: String): Future[(String, Seq[ElizaThreadInfo])]
  def keepAttribution(userId: Id[User], uriId: Id[NormalizedURI]): Seq[Id[User]]
  def hasThreads(userId: Id[User], url: String): Future[Boolean]
  def checkUrisDiscussed(userId: Id[User], uriIds: Seq[Id[NormalizedURI]]): Future[Seq[Boolean]]
  def sendMessageWithNonUserThread(nut: NonUserThread, messageText: String, source: Option[MessageSource], urlOpt: Option[URI])(implicit context: HeimdalContext): (MessageThread, ElizaMessage)
  def sendMessageWithUserThread(userThread: UserThread, messageText: String, source: Option[MessageSource], urlOpt: Option[URI])(implicit context: HeimdalContext): (MessageThread, ElizaMessage)
  def sendMessage(from: Id[User], thread: MessageThread, messageText: String, source: Option[MessageSource], urlOpt: Option[URI])(implicit context: HeimdalContext): (MessageThread, ElizaMessage)
  def sendMessageAction(title: Option[String], text: String, source: Option[MessageSource], userExtRecipients: Seq[ExternalId[User]], nonUserRecipients: Seq[BasicContact], validOrgRecipients: Seq[PublicId[Organization]],
    url: String, userId: Id[User], initContext: HeimdalContext): Future[(ElizaMessage, ElizaThreadInfo, Seq[MessageWithBasicUser])]
  def getNonUserThreadOpt(id: Id[NonUserThread]): Option[NonUserThread]
  def getNonUserThreadOptByAccessToken(token: ThreadAccessToken): Option[NonUserThread]
  def getUserThreadOptByAccessToken(token: ThreadAccessToken): Option[UserThread]
  def getThreads(user: Id[User], url: Option[String] = None): Seq[MessageThread]

  def setRead(userId: Id[User], messageId: Id[ElizaMessage])(implicit context: HeimdalContext): Unit
  def setUnread(userId: Id[User], messageId: Id[ElizaMessage]): Unit
  def setLastSeen(userId: Id[User], keepId: Id[Keep], timestampOpt: Option[DateTime] = None): Unit
  def setLastSeen(userId: Id[User], messageId: Id[ElizaMessage]): Unit
  def getUnreadUnmutedThreadCount(userId: Id[User]): Int
  def muteThreadForNonUser(id: Id[NonUserThread]): Boolean
  def unmuteThreadForNonUser(id: Id[NonUserThread]): Boolean
  def setUserThreadMuteState(userId: Id[User], keepId: Id[Keep], mute: Boolean)(implicit context: HeimdalContext): Boolean
  def setNonUserThreadMuteState(id: Id[NonUserThread], mute: Boolean): Boolean
  def addParticipantsToThread(adderUserId: Id[User], keepId: Id[Keep], newUsers: Seq[Id[User]], emailContacts: Seq[BasicContact],
    orgIds: Seq[Id[Organization]], newLibs: Seq[Id[Library]], source: Option[KeepEventSourceKind], updateShoebox: Boolean)(implicit context: HeimdalContext): Future[Boolean]

  def getChatter(userId: Id[User], urls: Seq[String]): Future[Map[String, Seq[Id[Keep]]]]
  def validateUsers(rawUsers: Seq[JsValue]): Seq[JsResult[ExternalId[User]]]
  def validateEmailContacts(rawNonUsers: Seq[JsValue]): Seq[JsResult[BasicContact]]
  def parseRecipients(rawRecipients: Seq[JsValue]): (Seq[ExternalId[User]], Seq[BasicContact], Seq[PublicId[Organization]])
}

class MessagingCommanderImpl @Inject() (
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
    // TODO(ryan): these notif classes are only here because of hacky NewKeep notification id collisions
    notifRepo: NotificationRepo,
    notifItemRepo: NotificationItemRepo,
    notifCommander: NotificationCommander,
    implicit val executionContext: ExecutionContext,
    implicit val publicIdConfig: PublicIdConfiguration,
    implicit val imageConfig: S3ImageConfig) extends MessagingCommander with Logging {

  private def buildThreadInfos(userId: Id[User], threads: Seq[MessageThread], requestUrl: String): Future[Seq[ElizaThreadInfo]] = {
    //get all involved users
    val allInvolvedUsers = threads.flatMap(_.participants.allUsers)
    //get all basic users
    val userId2BasicUserF = shoebox.getBasicUsers(allInvolvedUsers.toSeq)
    val discussionKeepsByKeepIdF = shoebox.getDiscussionKeepsByIds(userId, threads.map(_.keepId).toSet)
    //get all messages
    val messagesByThread: Map[Id[MessageThread], Seq[ElizaMessage]] = threads.map { thread =>
      (thread.id.get, basicMessageCommander.getThreadMessages(thread))
    }.toMap
    //get user_threads
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
          .map(nu => BasicUserLikeEntity(NonUserParticipant.toBasicNonUser(nu))).toSeq

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
        val threads = db.readOnlyReplica { implicit session =>
          val keepIds = userThreadRepo.getKeepIds(userId, nUri.id)
          val threadsByKeepId = threadRepo.getByKeepIds(keepIds.toSet)
          keepIds.map(threadsByKeepId(_))
        }
        buildThreadInfos(userId, threads, url).map { unsortedInfos =>
          val infos = unsortedInfos sortWith { (a, b) =>
            a.lastCommentedAt.compareTo(b.lastCommentedAt) < 0
          }
          (nUri.url, infos)
        }
      case Right(prenormalizedUrl) =>
        Future.successful((prenormalizedUrl, Seq[ElizaThreadInfo]()))
    })
  }

  def keepAttribution(userId: Id[User], uriId: Id[NormalizedURI]): Seq[Id[User]] = db.readOnlyReplica { implicit session =>
    val threads = userThreadRepo.getUserThreads(userId, uriId)
    val otherStarters = threads.collect {
      case ut if ut.lastSeen.exists(dt => dt.plusDays(3).isAfterNow) && ut.startedBy != userId => ut.startedBy
    }
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

  private def getOrCreateThread(from: Id[User], userParticipants: Seq[Id[User]], nonUserRecipients: Seq[NonUserParticipant], url: String, nUriId: Id[NormalizedURI], nUrl: String, titleOpt: Option[String]): Future[(MessageThread, Boolean)] = {
    val mtParticipants = MessageThreadParticipants(userParticipants.toSet, nonUserRecipients.toSet)
    val matches = db.readOnlyMaster { implicit s =>
      threadRepo.getByUriAndParticipants(nUriId, mtParticipants)
    }
    matches.headOption match {
      case Some(mt) => Future.successful(mt, false)
      case None =>
        shoebox.internKeep(from, userParticipants.toSet, nonUserRecipients.collect { case NonUserEmailParticipant(address) => address }.toSet, nUriId, url, titleOpt, None).map { csKeep =>
          db.readWrite { implicit s =>
            val thread = threadRepo.save(MessageThread(
              uriId = nUriId,
              url = url,
              nUrl = nUrl,
              pageTitle = titleOpt,
              startedBy = from,
              participants = mtParticipants,
              keepId = csKeep.id
            ))
            (thread, true)
          }
        }
    }
  }
  def sendNewMessage(from: Id[User], userRecipients: Seq[Id[User]], nonUserRecipients: Seq[NonUserParticipant], url: String, titleOpt: Option[String], messageText: String, source: Option[MessageSource])(implicit context: HeimdalContext): Future[(MessageThread, ElizaMessage)] = {
    updateMessageSearchHistoryWithEmailAddresses(from, nonUserRecipients)
    val userParticipants = (from +: userRecipients).distinct

    val uriFetch = URI.parse(url) match {
      case Success(parsed) =>
        shoebox.internNormalizedURI(parsed.toString(), contentWanted = true).map(n => (parsed, n.id.get, n.url, n.title))
      case Failure(e) => throw new Exception(s"can't send message for bad URL: [$url] from $from with title $titleOpt and source $source")
    }

    for {
      ((uri, nUriId, nUrl, nTitleOpt)) <- uriFetch
      (thread, isNew) <- getOrCreateThread(from, userParticipants, nonUserRecipients, url, nUriId, nUrl, titleOpt.orElse(nTitleOpt))
    } yield {
      if (isNew) {
        db.readWrite { implicit s =>
          checkEmailParticipantRateLimits(from, thread, nonUserRecipients)
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

  def sendMessageWithNonUserThread(nut: NonUserThread, messageText: String, source: Option[MessageSource], urlOpt: Option[URI])(implicit context: HeimdalContext): (MessageThread, ElizaMessage) = {
    log.info(s"Sending message from non-user with id ${nut.id} to keep ${nut.keepId}")
    val thread = db.readOnlyMaster { implicit session => threadRepo.getByKeepId(nut.keepId).get }
    sendMessage(MessageSender.NonUser(nut.participant), thread, messageText, source, urlOpt)
  }

  def sendMessageWithUserThread(userThread: UserThread, messageText: String, source: Option[MessageSource], urlOpt: Option[URI])(implicit context: HeimdalContext): (MessageThread, ElizaMessage) = {
    log.info(s"Sending message from user with id ${userThread.user} to keep ${userThread.keepId}")
    val thread = db.readOnlyMaster { implicit session => threadRepo.getByKeepId(userThread.keepId).get }
    sendMessage(MessageSender.User(userThread.user), thread, messageText, source, urlOpt)
  }

  def sendMessage(from: Id[User], thread: MessageThread, messageText: String, source: Option[MessageSource], urlOpt: Option[URI])(implicit context: HeimdalContext): (MessageThread, ElizaMessage) = {
    sendMessage(MessageSender.User(from), thread, messageText, source, urlOpt)
  }

  private def sendMessage(from: MessageSender, thread: MessageThread, messageText: String, source: Option[MessageSource], urlOpt: Option[URI], nUriIdOpt: Option[Id[NormalizedURI]] = None, isNew: Option[Boolean] = None)(implicit context: HeimdalContext): (MessageThread, ElizaMessage) = {
    from match {
      case MessageSender.User(id) =>
        if (!thread.containsUser(id)) throw NotAuthorizedException(s"User $id not authorized to send message on thread ${thread.id.get}")
      case MessageSender.NonUser(nup) =>
        if (!thread.containsNonUser(nup)) throw NotAuthorizedException(s"Non-User $nup not authorized to send message on thread ${thread.id.get}")
      case MessageSender.System =>
        throw NotAuthorizedException("Wrong code path for system Messages.")
    }

    log.info(s"Sending message from $from to ${thread.participants}")
    val message = db.readWrite { implicit session =>
      messageRepo.save(ElizaMessage(
        keepId = thread.keepId,
        from = from,
        messageText = messageText,
        source = source,
        sentOnUrl = urlOpt.flatMap(_.raw).orElse(Some(thread.url)),
        sentOnUriId = Some(thread.uriId)
      )) tap { msg => userThreadRepo.registerMessage(msg) }
    }
    SafeFuture {
      db.readOnlyMaster { implicit session => messageRepo.refreshCache(thread.keepId) }
      shoebox.registerMessageOnKeep(thread.keepId, ElizaMessage.toCrossServiceMessage(message))
      from.asUser.foreach(user => shoebox.editRecipientsOnKeep(editorId = user, keepId = thread.keepId, diff = KeepRecipientsDiff.addUser(user), persistKeepEvent = false, source = KeepEventSourceKind.fromMessageSource(source)))
    }

    val participantSet = thread.participants.allUsers
    val nonUserParticipantsSet = thread.participants.allNonUsers
    val id2BasicUser = Await.result(shoebox.getBasicUsers(participantSet.toSeq), 1.seconds) // todo: remove await
    val basicNonUserParticipants = nonUserParticipantsSet.map(NonUserParticipant.toBasicNonUser)
      .map(nu => BasicUserLikeEntity(nu))

    val sender = message.from match {
      case MessageSender.User(id) => Some(BasicUserLikeEntity(id2BasicUser(id)))
      case MessageSender.NonUser(nup) => Some(BasicUserLikeEntity(NonUserParticipant.toBasicNonUser(nup)))
      case _ => None
    }

    val messageWithBasicUser = MessageWithBasicUser(
      message.pubId,
      message.createdAt,
      message.messageText,
      source,
      None,
      message.sentOnUrl.getOrElse(""),
      thread.nUrl,
      message.from match {
        case MessageSender.User(id) => Some(BasicUserLikeEntity(id2BasicUser(id)))
        case MessageSender.NonUser(nup) => Some(BasicUserLikeEntity(NonUserParticipant.toBasicNonUser(nup)))
        case _ => Some(BasicUserLikeEntity(BasicUser(ExternalId[User]("42424242-4242-4242-4242-000000000001"), "Kifi", "", "0.jpg", Username("sssss"))))
      },
      participantSet.toSeq.map(u => BasicUserLikeEntity(id2BasicUser(u))) ++ basicNonUserParticipants
    )

    val author = message.from match {
      case MessageSender.User(id) => BasicAuthor.fromUser(id2BasicUser(id))
      case MessageSender.NonUser(nup) => BasicAuthor.fromNonUser(NonUserParticipant.toBasicNonUser(nup))
      case _ => BasicAuthor.Fake
    }

    val event = BasicKeepEvent.generateCommentEvent(message.pubId, author, message.messageText, message.createdAt, message.source)

    // send message through websockets immediately
    thread.participants.allUsers.foreach { user =>
      notificationDeliveryCommander.notifyMessage(user, message.pubKeepId, messageWithBasicUser)
      notificationDeliveryCommander.notifyEvent(user, message.pubKeepId, event)
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

    thread.allParticipants.foreach { userId =>
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
      val keepIds = userThreadRepo.getKeepIds(user)
      val threadsByKeepId = threadRepo.getByKeepIds(keepIds.toSet)
      keepIds.map(threadsByKeepId(_))
    }
  }

  def addParticipantsToThread(
    adderUserId: Id[User], keepId: Id[Keep],
    newUsers: Seq[Id[User]], emailContacts: Seq[BasicContact], orgIds: Seq[Id[Organization]], newLibs: Seq[Id[Library]],
    source: Option[KeepEventSourceKind], updateShoebox: Boolean)(implicit context: HeimdalContext): Future[Boolean] = {
    val newUserParticipantsFuture = Future.successful(newUsers)
    val newNonUserParticipantsFuture = constructNonUserRecipients(adderUserId, emailContacts)

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

        val oldThread = threadRepo.getByKeepId(keepId).get

        checkEmailParticipantRateLimits(adderUserId, oldThread, newNonUserParticipants)

        if (!oldThread.participants.contains(adderUserId)) {
          throw NotAuthorizedException(s"User $adderUserId not authorized to add participants to keep $keepId")
        }

        val actuallyNewUsers = (newUserParticipants ++ newOrgParticipants).filterNot(oldThread.containsUser)
        val actuallyNewNonUsers = newNonUserParticipants.filterNot(oldThread.containsNonUser)
        val actuallyNewLibraries = {
          if (newLibs.isEmpty) Set.empty[Id[Library]]
          else {
            val existingLibs = messageRepo.getAllByKeep(keepId).foldLeft(Set.empty[Id[Library]]) {
              case (allLibs, message) =>
                message.auxData match {
                  case Some(SystemMessageData.AddLibraries(_, libraries)) => allLibs ++ libraries
                  case _ => allLibs
                }
            }
            newLibs.filterNot(existingLibs.contains).toSet
          }
        }

        if (actuallyNewLibraries.nonEmpty) {
          messageRepo.save(ElizaMessage(
            keepId = oldThread.keepId,
            from = MessageSender.System,
            messageText = "",
            source = source.flatMap(KeepEventSourceKind.toMessageSource),
            auxData = Some(AddLibraries(adderUserId, actuallyNewLibraries)),
            sentOnUrl = None,
            sentOnUriId = None
          ))
        }

        if (actuallyNewNonUsers.isEmpty && actuallyNewUsers.isEmpty) {
          None
        } else {
          val thread = threadRepo.save(oldThread.withParticipants(clock.now, actuallyNewUsers.toSet, actuallyNewNonUsers.toSet))
          val message = messageRepo.save(ElizaMessage(
            keepId = thread.keepId,
            from = MessageSender.System,
            messageText = "",
            source = source.flatMap(KeepEventSourceKind.toMessageSource),
            auxData = Some(AddParticipants(adderUserId, actuallyNewUsers, actuallyNewNonUsers)),
            sentOnUrl = None,
            sentOnUriId = None
          ))

          actuallyNewUsers.foreach(pUserId => userThreadRepo.intern(UserThread.forMessageThread(thread)(user = pUserId)))
          actuallyNewNonUsers.foreach { nup =>
            nonUserThreadRepo.save(NonUserThread(
              createdBy = adderUserId,
              participant = nup,
              keepId = thread.keepId,
              uriId = Some(thread.uriId),
              notifiedCount = 0,
              lastNotifiedAt = None,
              threadUpdatedByOtherAt = Some(message.createdAt),
              muted = false
            ))
          }

          Some((actuallyNewUsers, actuallyNewNonUsers, actuallyNewLibraries, message, thread))

        }
      }

      resultInfoOpt.exists {
        case (newUsers, newNonUsers, newLibraries, message, thread) =>

          SafeFuture {
            db.readOnlyMaster { implicit session =>
              messageRepo.refreshCache(thread.keepId)
            }
          }

          if (updateShoebox) {
            val newEmails = newNonUsers.flatMap(NonUserParticipant.toEmailAddress)
            shoebox.editRecipientsOnKeep(adderUserId, keepId, KeepRecipientsDiff(DeltaSet.addOnly(newUsers.toSet), DeltaSet.addOnly(newLibraries), DeltaSet.addOnly(newEmails.toSet)), persistKeepEvent = true, source)
          }

          if (newUsers.nonEmpty || newNonUsers.nonEmpty) {
            notificationDeliveryCommander.notifyAddParticipants(newUsers, newNonUsers, thread, message, adderUserId)
            messagingAnalytics.addedParticipantsToConversation(adderUserId, newUsers, newNonUsers, thread, context)
          }

          true

      }

    }

    new SafeFuture[Boolean](haveBeenAdded, Some("Adding Participants to Thread"))
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

    notificationDeliveryCommander.notifyRead(userId, message.keepId, message.id.get, thread.nUrl, message.createdAt)
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
      notificationDeliveryCommander.notifyUnread(userId, message.keepId, message.id.get, thread.nUrl, message.createdAt)
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

  def getChatter(userId: Id[User], urls: Seq[String]): Future[Map[String, Seq[Id[Keep]]]] = {
    implicit val timeout = Duration(3, "seconds")
    TimeoutFuture(Future.sequence(urls.map(u => shoebox.getNormalizedURIByURL(u).map(n => u -> n)))).recover {
      case ex: TimeoutException => Seq[(String, Option[NormalizedURI])]()
    }.map { res =>
      db.readOnlyReplica { implicit session =>
        res.collect {
          case (url, Some(nuri)) =>
            url -> userThreadRepo.getKeepIds(userId, Some(nuri.id.get))
        }
      }.toMap
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
    initContext: HeimdalContext): Future[(ElizaMessage, ElizaThreadInfo, Seq[MessageWithBasicUser])] = {
    val tStart = currentDateTime

    val userRecipientsFuture = shoebox.getUserIdsByExternalIds(userExtRecipients.toSet).map(_.values.toSeq)
    val nonUserRecipientsFuture = constructNonUserRecipients(userId, nonUserRecipients)

    val orgIds = validOrgRecipients.map(o => Organization.decodePublicId(o)).filter(_.isSuccess).map(_.get)

    val canSendToOrgs = shoebox.getUserPermissionsByOrgId(orgIds.toSet, userId).map { permissionsByOrgId =>
      orgIds.forall { orgId =>
        val ok = permissionsByOrgId(orgId).contains(OrganizationPermission.GROUP_MESSAGING)
        if (!ok) airbrake.notify(s"user $userId was able to send to org $orgId without permissions!")
        ok
      }
    }

    val moreContext = new HeimdalContextBuilder()
    val orgParticipantsFuture = Future.sequence(orgIds.map { oid =>
      shoebox.getOrganizationMembers(oid)
    }).map(_.flatten)

    if (orgIds.nonEmpty) moreContext += ("messagedAllOrgId", Random.shuffle(orgIds).head.toString)

    val context = moreContext.addExistingContext(initContext).build

    val resFut =
      canSendToOrgs.flatMap { canSend =>
        if (!canSend) throw new Exception("insufficient_org_permissions")
        else {
          for {
            userRecipients <- userRecipientsFuture
            nonUserRecipients <- nonUserRecipientsFuture
            orgParticipants <- orgParticipantsFuture
            (thread, message) <- sendNewMessage(userId, userRecipients ++ orgParticipants, nonUserRecipients, url, title, text, source)(context)
            messagesWithBasicUser <- basicMessageCommander.getThreadMessagesWithBasicUser(thread)
            Seq(threadInfo) <- buildThreadInfos(userId, Seq(thread), url)
          } yield {
            val actions = userRecipients.map(id => (Left(id), "message")) ++ nonUserRecipients.collect {
              case NonUserEmailParticipant(address) => (Right(address), "message")
            }
            shoebox.addInteractions(userId, actions)

            val tDiff = currentDateTime.getMillis - tStart.getMillis
            statsd.timing(s"messaging.newMessage", tDiff, ONE_IN_HUNDRED)
            (message, threadInfo, messagesWithBasicUser)
          }
        }
      }
    resFut
  }

  def validateUsers(rawUsers: Seq[JsValue]): Seq[JsResult[ExternalId[User]]] = rawUsers.map(_.validate[ExternalId[User]])
  def validateEmailContacts(rawNonUsers: Seq[JsValue]): Seq[JsResult[BasicContact]] = rawNonUsers.map(_.validate[JsObject].map {
    case obj if (obj \ "kind").as[String] == "email" => (obj \ "email").as[BasicContact]
  })

  def parseRecipients(rawRecipients: Seq[JsValue]): (Seq[ExternalId[User]], Seq[BasicContact], Seq[PublicId[Organization]]) = {
    val rawCategorized = rawRecipients.flatMap {
      case JsString(id) if id.startsWith("o") => Organization.validatePublicId(id).toOption
      case JsString(id) if id.length == 36 => ExternalId.asOpt[User](id)
      case recip: JsObject if (recip \ "kind").asOpt[String].contains("user") => (recip \ "id").asOpt[ExternalId[User]]
      case recip: JsObject if (recip \ "kind").asOpt[String].contains("org") => (recip \ "id").asOpt[PublicId[Organization]]
      case recip: JsObject if (recip \ "kind").asOpt[String].contains("email") => (recip \ "email").asOpt[BasicContact]
      case unknown =>
        log.warn(s"[validateRecipients] Could not determine what ${Json.stringify(unknown)} is supposed to be.")
        None
    }
    (
      rawCategorized.collect { case userId: ExternalId[User] @unchecked => userId },
      rawCategorized.collect { case bc: BasicContact => bc },
      rawCategorized.collect { case orgId: PublicId[Organization] @unchecked => orgId }
    )
  }

  private def checkEmailParticipantRateLimits(user: Id[User], thread: MessageThread, nonUsers: Seq[NonUserParticipant])(implicit session: RSession): Unit = {

    // Check rate limit for this discussion
    val distinctEmailRecipients = nonUsers.collect { case emailParticipant: NonUserEmailParticipant => emailParticipant.address }.toSet
    val existingEmailParticipants = thread.participants.allNonUsers.collect { case emailParticipant: NonUserEmailParticipant => emailParticipant.address }

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
