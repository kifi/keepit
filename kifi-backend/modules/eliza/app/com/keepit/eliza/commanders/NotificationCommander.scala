package com.keepit.eliza.commanders

import com.keepit.common.db.{ExternalId, Id}
import com.keepit.model.{NotificationCategory, User, URISummaryRequest, URISummary, ImageType}
import com.keepit.eliza.model._
import com.keepit.eliza.model.NonUserThread._
import com.keepit.common.akka.SafeFuture
import scala.util.Try
import play.api.libs.json._
import com.google.inject.Inject
import com.keepit.common.logging.Logging
import com.keepit.common.db.slick.Database
import com.keepit.eliza.controllers.WebSocketRouter
import com.keepit.realtime.UrbanAirship
import org.joda.time.DateTime
import com.keepit.eliza.mail.MessageLookHereRemover
import com.keepit.shoebox.ShoeboxServiceClient
import java.nio.{ByteBuffer, CharBuffer}
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets.UTF_8
import scala.concurrent.{Promise, Future, Await}
import com.keepit.common.time._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.JsArray
import com.keepit.eliza.model.UserThreadActivity
import scala.util.Failure
import scala.Some
import scala.util.Success
import com.keepit.eliza.model.UserThread
import com.keepit.eliza.model.Notification
import play.api.libs.json.JsObject
import com.keepit.realtime.PushNotification
import com.keepit.social.{BasicUserLikeEntity, BasicUser, BasicNonUser, NonUserKinds}
import com.keepit.common.mail.{ElectronicMail, ElectronicMailCategory, EmailAddresses, GenericEmailAddress, EmailAddressHolder, PostOffice}
import com.keepit.common.crypto.PublicIdConfiguration
import scala.concurrent.duration._
import com.keepit.common.net.URI
import com.keepit.common.concurrent.FutureHelpers
import com.keepit.common.store.ImageSize
import scala.util.matching.Regex._
import java.net.URLDecoder

class NotificationCommander @Inject() (
  threadRepo: MessageThreadRepo,
  userThreadRepo: UserThreadRepo,
  nonUserThreadRepo: NonUserThreadRepo,
  messageRepo: MessageRepo,
  db: Database,
  notificationRouter: WebSocketRouter,
  shoebox: ShoeboxServiceClient,
  messagingAnalytics: MessagingAnalytics,
  urbanAirship: UrbanAirship,
  notificationUpdater: NotificationUpdater,
  basicMessageCommander: MessageFetchingCommander,
  implicit val publicIdConfig: PublicIdConfiguration) extends Logging  {

  def notifySendMessage(from: Id[User], message: Message, thread: MessageThread, orderedMessageWithBasicUser: MessageWithBasicUser, originalAuthor: Int, numAuthors: Int, numMessages: Int, numUnread: Int): Unit = {
    val notifJson = buildMessageNotificationJson(
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
    sendToUser(from, Json.arr("notification", notifJson))
  }

  //temp, just for internal testing. Functionality broken up and moving to it's own commander/plugin once the semantics are finalized (pre ship)
  def notifyEmailUsers(thread: MessageThread): Unit = if (thread.participants.exists(!_.allNonUsers.isEmpty)) {
    case class CleanedMessage(cleanText: String, lookHereTexts: Seq[String], lookHereImageUrls: Seq[String])

    def parseMessage(msg: String): CleanedMessage = {
      val re = """\[((?:\\\]|[^\]])*)\](\(x-kifi-sel:((?:\\\)|[^)])*)\))""".r
      var textLookHeres = Vector[String]()
      var imageLookHereUrls = Vector[String]()
      re.findAllMatchIn(msg).toSeq.toList.foreach { m =>
        val segments = m.group(3).split('|')
        val kind = segments.head
        val payload = URLDecoder.decode(segments.last, "UTF-8")
        kind match {
          case "i" => imageLookHereUrls = imageLookHereUrls :+ payload
          case "r" => textLookHeres = textLookHeres :+ payload
          case _ => throw new Exception("Unknow look-here type: " + kind)
        }
      }
      CleanedMessage( re.replaceAllIn(msg, (m: Match) => m.group(1)), textLookHeres, imageLookHereUrls)
    }

    val (nuts, starterUserId) = db.readOnly { implicit session =>
      (nonUserThreadRepo.getByMessageThreadId(thread.id.get),
        userThreadRepo.getThreadStarter(thread.id.get)
      )
    }

    val allUserIds : Set[Id[User]] = thread.participants.map(_.allUsers).getOrElse(Set.empty)


    val allUsersFuture : Future[Map[Id[User], User]] = shoebox.getUsers(allUserIds.toSeq).map( s => s.map(u => u.id.get -> u).toMap)

    val allUserImageUrlsFuture : Future[Map[Id[User], String]] = FutureHelpers.map(allUserIds.map( u => u -> shoebox.getUserImageUrl(u, 73)).toMap)

    val uriSummaryFuture : Future[URISummary] = shoebox.getUriSummary(URISummaryRequest(
      url = thread.nUrl.get,
      imageType = ImageType.ANY,
      minSize = ImageSize(183, 96),
      withDescription = true,
      waiting = true,
      silent = false))

    for (allUsers <- allUsersFuture; allUserImageUrls <- allUserImageUrlsFuture; uriSummary <- uriSummaryFuture) {

      val starterUser = allUsers(starterUserId)

      val participants = allUsers.values.map{ u => u.fullName } ++ nuts.map{ nut => nut.participant.fullName }

      val pageName = thread.nUrl.flatMap( url => URI.parse(url).toOption.flatMap( uri => uri.host.map(_.name)) ).get

      val messages = basicMessageCommander.getThreadMessages(thread)

      nuts.filterNot(_.muted).foreach{ nut =>
        require(nut.participant.kind == NonUserKinds.email)

        shoebox.getUnsubscribeUrlForEmail(nut.participant.identifier).map{ unsubUrl =>

          val threadInfo = ThreadEmailInfo(
            pageUrl = thread.nUrl.get,
            pageName = pageName,
            pageTitle = uriSummary.title.getOrElse(thread.nUrl.get),
            heroImageUrl = uriSummary.imageUrl,
            pageDescription = uriSummary.description,
            participants = participants.toSeq,
            conversationStarter = starterUser.firstName + " " + starterUser.lastName,
            unsubUrl = unsubUrl,
            muteUrl = "https://www.kifi.com/extmsg/email/mute?publicId=" + nut.accessToken.token //Todo (Stephen): remove hard coded url with public id
          )

          var relevantMessages = messages
          nut.lastNotifiedAt.map { dt =>
            relevantMessages = relevantMessages.filter{ m =>
              m.createdAt.isAfter(dt.minusSeconds(2))  //Note (Stephen): Potential race condition here if a message is send right as the email is sent. Needs more thought.
            }
          }

          val threadItems = relevantMessages.filterNot(_.from.isSystem).map{ message =>
            val CleanedMessage(text, lookHereTexts, lookHereImageUrls) = parseMessage(message.messageText)
            message.from match {
              case MessageSender.User(id) => ExtendedThreadItem(allUsers(id).shortName, allUsers(id).fullName, allUserImageUrls(id), text, lookHereTexts, lookHereImageUrls)
              case MessageSender.NonUser(nup) => {
                ExtendedThreadItem(nup.shortName, nup.fullName, "//www.kifi.com/assets/img/ghost.200.png", text, lookHereTexts, Seq.empty)
              }
              case _ => throw new Exception("Impossible")
            }
          }.reverse

          val body = views.html.nonUserDigestEmail(threadInfo, threadItems).body

          val magicAddress = EmailAddresses.discussion(nut.accessToken.token)
          shoebox.sendMail(ElectronicMail (
            from = magicAddress,
            fromName = Some("Kifi Discussions"),
            to = Seq[EmailAddressHolder](GenericEmailAddress(nut.participant.identifier)),
            subject = "Kifi Message on " + pageName,
            htmlBody = body,
            category = ElectronicMailCategory("external_message_test"),
            extraHeaders = Some(Map(PostOffice.Headers.REPLY_TO -> magicAddress.address))
          ))
          db.readWrite{ implicit session => nonUserThreadRepo.setLastNotifiedAndIncCount(nut.id.get, currentDateTime) }
        }

      }

    }

  }

  def notifyAddParticipants(newParticipants: Seq[Id[User]], newNonUserParticipants: Seq[NonUserParticipant], thread: MessageThread, message: Message, adderUserId: Id[User]): Unit = {
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

        newNonUserParticipants.map { nup =>
          val nut = nonUserThreadRepo.save(NonUserThread(
            participant = nup,
            threadId = thread.id.get,
            uriId = thread.uriId,
            notifiedCount = 0,
            lastNotifiedAt = None,
            threadUpdatedAt = Some(thread.createdAt),
            muted = false
          ))
        }
      }

      Future.sequence(newParticipants.map { userId =>
        recreateNotificationForAddedParticipant(userId, thread)
      }) map { permanentNotifications =>
        newParticipants.zip(permanentNotifications) map { case (userId, permanentNotification) =>
          sendToUser(userId, Json.arr("notification", notificationJson, permanentNotification))
        }
        val messageWithBasicUser = basicMessageCommander.getMessageWithBasicUser(message.externalId, message.createdAt, "", message.source, message.auxData, "", "", None, participants)
        messageWithBasicUser.map { augmentedMessage =>
          thread.participants.map(_.allUsers.par.foreach { userId =>
            sendToUser(userId, Json.arr("message", thread.externalId.id, augmentedMessage))
            sendToUser(userId, Json.arr("thread_participants", thread.externalId.id, participants))
          })
        }
        notifyEmailUsers(thread)
      }
    })
  }

  def notifyMessage(userId: Id[User], threadExtId: ExternalId[MessageThread], message: MessageWithBasicUser): Unit =
    sendToUser(userId, Json.arr("message", threadExtId.id, message))

  def notifyRead(userId: Id[User], threadExtId: ExternalId[MessageThread], msgExtId: ExternalId[Message], nUrl: String, creationDate: DateTime, unreadCount: Int): Unit = {
    sendToUser(userId, Json.arr("message_read", nUrl, threadExtId.id, creationDate, msgExtId.id))
    notifyUnreadCount(userId, threadExtId, unreadCount)
  }

  def notifyUnread(userId: Id[User], threadExtId: ExternalId[MessageThread], msgExtId: ExternalId[Message], nUrl: String, creationDate: DateTime, unreadCount: Int): Unit = {
    sendToUser(userId, Json.arr("message_unread", nUrl, threadExtId.id, creationDate, msgExtId.id))
    notifyUnreadCount(userId, threadExtId, unreadCount)
  }

  private def notifyUnreadCount(userId: Id[User], threadExtId: ExternalId[MessageThread], unreadCount: Int): Unit = {
    sendToUser(userId, Json.arr("unread_notifications_count", unreadCount))
    val notification = PushNotification(threadExtId, unreadCount, None, None)
    sendPushNotification(userId, notification)
  }

  def notifyRemoveThread(userId: Id[User], threadExtId: ExternalId[MessageThread]): Unit =
    sendToUser(userId, Json.arr("remove_thread", threadExtId.id))

  private def sendToUser(userId: Id[User], data: JsArray) : Unit =
    notificationRouter.sendToUser(userId, data)

  def createGlobalNotification(userIds: Set[Id[User]], title: String, body: String, linkText: String, linkUrl: String, imageUrl: String, sticky: Boolean, category: NotificationCategory) = {
    val (message, thread) = db.readWrite { implicit session =>
      val mtps = MessageThreadParticipants(userIds)
      val thread = threadRepo.save(MessageThread(
        uriId = None,
        url = None,
        nUrl = None,
        pageTitle = None,
        participants = Some(mtps),
        participantsHash = Some(mtps.hash),
        replyable = false
      ))

      val message = messageRepo.save(Message(
        from = MessageSender.System,
        thread = thread.id.get,
        threadExtId = thread.externalId,
        messageText = s"$title (on $linkText): $body",
        source = Some(MessageSource.SERVER),
        sentOnUrl = Some(linkUrl),
        sentOnUriId = None
      ))

      (message, thread)
    }
    SafeFuture {
      val notificationAttempts = userIds.map { userId =>
        Try {
          val categoryString = NotificationCategory.User.kifiMessageFormattingCategory.get(category) getOrElse "global"
          val notifJson = Json.obj(
            "id"       -> message.externalId.id,
            "time"     -> message.createdAt,
            "thread"   -> message.threadExtId.id,
            "unread"   -> true,
            "category" -> categoryString,
            "title"    -> title,
            "bodyHtml" -> body,
            "linkText" -> linkText,
            "url"      -> linkUrl,
            "isSticky" -> sticky,
            "image"    -> imageUrl
          )
          notificationRouter.sendToUser(userId, Json.arr("notification", notifJson))

          db.readWrite{ implicit session =>
            userThreadRepo.save(UserThread(
              id = None,
              user = userId,
              thread = thread.id.get,
              uriId = None,
              lastSeen = None,
              unread = true,
              lastMsgFromOther = Some(message.id.get),
              lastNotification = notifJson,
              notificationUpdatedAt = message.createdAt,
              replyable = false
            ))
          }
          userId
        }
      }

      val notified = notificationAttempts collect { case Success(userId) => userId }
      messagingAnalytics.sentGlobalNotification(notified, message, thread, category)

      val errors = notificationAttempts collect { case Failure(ex) => ex }
      if (errors.size>0) throw scala.collection.parallel.CompositeThrowable(errors)
    }
    message.id.get
  }


  private def buildMessageNotificationJson(
    message: Message,
    thread: MessageThread,
    messageWithBasicUser: MessageWithBasicUser,
    locator: String,
    unread: Boolean,
    originalAuthorIdx: Int,
    unseenAuthors: Int,
    numAuthors: Int,
    numMessages: Int,
    numUnread: Int,
    muted: Boolean
    ) : JsValue = {
    Json.obj(
      "id"            -> message.externalId.id,
      "time"          -> message.createdAt,
      "thread"        -> thread.externalId.id,
      "text"          -> message.messageText,
      "url"           -> thread.nUrl,
      "title"         -> thread.pageTitle,
      "author"        -> messageWithBasicUser.user,
      "participants"  -> messageWithBasicUser.participants,
      "locator"       -> locator,
      "unread"        -> unread,
      "category"      -> NotificationCategory.User.MESSAGE.category,
      "firstAuthor"   -> originalAuthorIdx,
      "authors"       -> numAuthors, //number of people who have sent messages in this conversation
      "messages"      -> numMessages, //total number of messages in this conversation
      "unreadAuthors" -> unseenAuthors, //number of people in 'participants' whose messages user hasn't seen yet
      "unreadMessages"-> numUnread,
      "muted"         -> muted
    )
  }

  def sendPushNotification(userId:Id[User], notification: PushNotification) = {
    urbanAirship.notifyUser(userId, notification)
    messagingAnalytics.sentPushNotificationForThread(userId, notification)
  }

  def sendNotificationForMessage(userId: Id[User], message: Message, thread: MessageThread, messageWithBasicUser: MessageWithBasicUser, orderedActivityInfo: Seq[UserThreadActivity], unreadCount: Int) : Unit = {
    SafeFuture {
      val authorActivityInfos = orderedActivityInfo.filter(_.lastActive.isDefined)
      val lastSeenOpt: Option[DateTime] = orderedActivityInfo.filter(_.userId==userId).head.lastSeen
      val unseenAuthors: Int = lastSeenOpt match {
        case Some(lastSeen) => authorActivityInfos.count(_.lastActive.get.isAfter(lastSeen))
        case None => authorActivityInfos.length
      }
      val (numMessages: Int, numUnread: Int, muted: Boolean) = db.readOnly { implicit session =>
        val (numMessages, numUnread) = messageRepo.getMessageCounts(thread.id.get, lastSeenOpt)
        val muted = userThreadRepo.isMuted(userId, thread.id.get)
        (numMessages, numUnread, muted)
      }

      val notifJson = buildMessageNotificationJson(
        message = message,
        thread = thread,
        messageWithBasicUser = messageWithBasicUser,
        locator = thread.deepLocator.value,
        unread = true,
        originalAuthorIdx = authorActivityInfos.filter(_.started).zipWithIndex.head._2,
        unseenAuthors = unseenAuthors,
        numAuthors = authorActivityInfos.length,
        numMessages = numMessages,
        numUnread = numUnread,
        muted = muted)

      db.readWrite(attempts = 2){ implicit session =>
        userThreadRepo.setNotification(userId, thread.id.get, message, notifJson, !muted)
      }

      messagingAnalytics.sentNotificationForMessage(userId, message, thread, muted)
      message.from.asUser.map(
        shoebox.createDeepLink(_, userId, thread.uriId.get, thread.deepLocator)
      )

      notificationRouter.sendToUser(userId, Json.arr("notification", notifJson))
      notificationRouter.sendToUser(userId, Json.arr("unread_notifications_count", unreadCount))

      if (!muted) {
        val sender = messageWithBasicUser.user match {
          case Some(bu:BasicUser) => bu.firstName + ": "
          case Some(bnu:BasicNonUser) => bnu.firstName.getOrElse(bnu.id) + ": "
          case _ => ""
        }
        val notifText = MessageLookHereRemover(sender + message.messageText)
        val sound = if (numMessages > 1) UrbanAirship.MoreMessageNotificationSound else UrbanAirship.DefaultNotificationSound
        val notification = PushNotification(thread.externalId, unreadCount, Some(trimAtBytes(notifText, 128, UTF_8)), Some(sound))
        sendPushNotification(userId, notification)
      }
    }

    //This is mostly for testing and monitoring
    notificationRouter.sendNotification(Some(userId), Notification(thread.id.get, message.id.get))
  }

  private def trimAtBytes(str: String, len: Int, charset: Charset) = { //Conner's Algorithm
  val outBuf = ByteBuffer.wrap(new Array[Byte](len))
    val inBuf = CharBuffer.wrap(str.toCharArray())
    charset.newEncoder().encode(inBuf, outBuf, true)
    new String(outBuf.array, 0, outBuf.position(), charset)
  }

  //for a given user and thread make sure the notification is correct
  private def recreateNotificationForAddedParticipant(userId: Id[User], thread: MessageThread) : Future[JsValue]  = {
    val message = db.readOnly { implicit session => messageRepo.getLatest(thread.id.get) }

    val participantSet = thread.participants.map(_.allUsers).getOrElse(Set())
    new SafeFuture(shoebox.getBasicUsers(participantSet.toSeq).map { id2BasicUser =>

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

      val orderedMessageWithBasicUser = MessageWithBasicUser(
        message.externalId,
        message.createdAt,
        message.messageText,
        message.source,
        None,
        message.sentOnUrl.getOrElse(""),
        thread.nUrl.getOrElse(""), //TODO Stephen: This needs to change when we have detached threads
        message.from match {
          case MessageSender.User(id) => Some(id2BasicUser(id))
          case MessageSender.NonUser(nup) => Some(NonUserParticipant.toBasicNonUser(nup))
          case _ => None
        },
        threadActivity.map{ ta => id2BasicUser(ta.userId)} ++ nonUsers
      )

      val notifJson = buildMessageNotificationJson(
        message = message,
        thread = thread,
        messageWithBasicUser = orderedMessageWithBasicUser,
        locator = "/messages/" + thread.externalId,
        unread = true,
        originalAuthorIdx = originalAuthor,
        unseenAuthors = numAuthors,
        numAuthors = numAuthors,
        numMessages = numMessages,
        numUnread = numUnread,
        muted = false
      )

      db.readWrite(attempts=2){ implicit session =>
        userThreadRepo.setNotification(userId, thread.id.get, message, notifJson, true)
      }

      messagingAnalytics.sentNotificationForMessage(userId, message, thread, false)
      message.from.asUser.map(
        shoebox.createDeepLink(_, userId, thread.uriId.get, thread.deepLocator)
      )

      notifJson
    })
  }

  def setAllNotificationsRead(userId: Id[User]): Unit = {
    log.info(s"Setting all Notifications as read for user $userId.")
    db.readWrite(attempts=2) {implicit session => userThreadRepo.markAllRead(userId)}
  }

  def setAllNotificationsReadBefore(user: Id[User], messageId: ExternalId[Message], unreadCount: Int) : DateTime = {
    val message = db.readWrite(attempts=2) { implicit session =>
      val message = messageRepo.get(messageId)
      userThreadRepo.markAllReadAtOrBefore(user, message.createdAt)
      message
    }
    notificationRouter.sendToUser(user, Json.arr("unread_notifications_count", unreadCount))
    val notification = PushNotification(message.threadExtId, unreadCount, None, None)
    sendPushNotification(user, notification)
    message.createdAt
  }


  def getUnreadThreadNotifications(userId: Id[User]) : Seq[Notification] = {
    db.readOnly { implicit session =>
      userThreadRepo.getUnreadThreadNotifications(userId)
    }
  }

  def getSendableNotification(userId: Id[User], threadExtId: ExternalId[MessageThread]): Future[JsObject] = {
    notificationUpdater.update(db.readOnly { implicit session =>
      val thread = threadRepo.get(threadExtId)
      userThreadRepo.getSendableNotification(userId, thread.id.get)
    })
  }

  def getLatestSendableNotifications(userId: Id[User], howMany: Int): Future[Notifications] = {
    notificationUpdater.update(db.readOnly { implicit session =>
      userThreadRepo.getLatestSendableNotifications(userId, howMany)
    })
  }

  def getSendableNotificationsBefore(userId: Id[User], time: DateTime, howMany: Int): Future[Notifications] = {
    notificationUpdater.update(db.readOnly { implicit session =>
      userThreadRepo.getSendableNotificationsBefore(userId, time, howMany)
    })
  }

  def getLatestUnreadSendableNotifications(userId: Id[User], howMany: Int): Future[(Notifications, Int)] = {
    val noticesFuture = notificationUpdater.update(db.readOnly { implicit session =>
      userThreadRepo.getLatestUnreadSendableNotifications(userId, howMany)
    })
    new SafeFuture(noticesFuture map { notices =>
      val numTotal = if (notices.jsons.length < howMany) {
        notices.jsons.length
      } else {
        db.readOnly { implicit session =>
          userThreadRepo.getUnreadThreadCount(userId)
        }
      }
      (notices, numTotal)
    })
  }

  def getUnreadSendableNotificationsBefore(userId: Id[User], time: DateTime, howMany: Int): Future[Notifications] = {
    notificationUpdater.update(db.readOnly { implicit session =>
      userThreadRepo.getUnreadSendableNotificationsBefore(userId, time, howMany)
    })
  }

  def getLatestSentSendableNotifications(userId: Id[User], howMany: Int): Future[Notifications] = {
    notificationUpdater.update(db.readOnly { implicit session =>
      userThreadRepo.getLatestSendableNotificationsForStartedThreads(userId, howMany)
    })
  }

  def getSentSendableNotificationsBefore(userId: Id[User], time: DateTime, howMany: Int): Future[Notifications] = {
    notificationUpdater.update(db.readOnly { implicit session =>
      userThreadRepo.getSendableNotificationsForStartedThreadsBefore(userId, time, howMany)
    })
  }

  def getLatestSendableNotificationsForPage(userId: Id[User], url: String, howMany: Int): Future[(String, Notifications, Int, Int)] = {
    new SafeFuture(shoebox.getNormalizedUriByUrlOrPrenormalize(url) flatMap {
      case Left(nUri) =>
        val noticesFuture = notificationUpdater.update(db.readOnly { implicit session =>
          userThreadRepo.getLatestSendableNotificationsForUri(userId, nUri.id.get, howMany)
        })
        new SafeFuture(noticesFuture map { notices =>
          val (numTotal, numUnreadUnmuted): (Int, Int) = if (notices.jsons.length < howMany) {
            (notices.jsons.length, notices.jsons.count { n =>
              (n \ "unread").asOpt[Boolean].getOrElse(false) &&
                !(n \ "muted").asOpt[Boolean].getOrElse(false)
            })
          } else {
            db.readOnly { implicit session =>
              userThreadRepo.getThreadCountsForUri(userId, nUri.id.get)
            }
          }
          (nUri.url, notices, numTotal, numUnreadUnmuted)
        })
      case Right(prenormalizedUrl) =>
        Promise.successful(prenormalizedUrl, Notifications(Seq.empty), 0, 0).future
    })
  }

  def getSendableNotificationsForPageBefore(userId: Id[User], url: String, time: DateTime, howMany: Int): Future[Notifications] = {
    new SafeFuture(shoebox.getNormalizedURIByURL(url) flatMap {
      case Some(nUri) =>
        notificationUpdater.update(db.readOnly { implicit session =>
          userThreadRepo.getSendableNotificationsForUriBefore(userId, nUri.id.get, time, howMany)
        })
      case _ => Promise.successful(Notifications(Seq.empty)).future
    })
  }

  def connectedSockets: Int = notificationRouter.connectedSockets

  def notifyUserAboutMuteChange(userId: Id[User], threadId: ExternalId[MessageThread], mute: Boolean) = {
    notificationRouter.sendToUser(userId, Json.arr("thread_muted", threadId.id, mute))
  }
}
