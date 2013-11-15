package com.keepit.eliza

import com.keepit.model.{UserNotification, User, DeepLocator, NormalizedURI}
import com.keepit.common.db.{Id, ExternalId}
import com.keepit.common.db.slick.{Database}
import com.keepit.shoebox.{ShoeboxServiceClient}
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.social.BasicUser
import com.keepit.common.controller.ElizaServiceController
import com.keepit.common.akka.SafeFuture

import scala.concurrent.{Promise, future, Await, Future}
import scala.concurrent.duration._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.http.Status.ACCEPTED

import com.google.inject.Inject

import org.joda.time.DateTime

import play.api.libs.json.{JsValue, JsString, JsArray, Json}
import play.modules.statsd.api.Statsd

import java.nio.{ByteBuffer, CharBuffer}
import java.nio.charset.Charset
import com.keepit.common.akka.TimeoutFuture
import java.util.concurrent.TimeoutException
import com.keepit.realtime.{PushNotification, UrbanAirship}
import play.api.libs.json.JsArray
import play.api.libs.json.JsObject
import com.keepit.realtime.PushNotification
import com.keepit.common.KestrelCombinator
import scala.util.Try


//For migration only
import play.api.mvc.Action
import com.keepit.common.db.slick.DBSession.RWSession

/* To future maintainers
*  If this is ever getting too slow the first things I would look at (in no particular order):
*  -External Id lookups. Make those numeric Id (instead of giant strings) and index them in the db
*  -Synchronous dependency on normalized url id in thread creation. Remove it.
*  -Go to 64 bit for the participants hash
*  -Use "Insert Ignore" where possible
*  -Lazy creation of UserThread rows
*  -Do batch updates when modifying UserThreads for a given thread (will require index on threadid)
*  -Make notifications not wait for a message ID
*/

case class NotAuthorizedException(msg: String) extends java.lang.Throwable

object MessagingController {
  val THREAD_PAGE_SIZE = 20
}

class MessagingController @Inject() (
    protected val threadRepo: MessageThreadRepo,
    userThreadRepo: UserThreadRepo,
    protected val messageRepo: MessageRepo,
    protected val shoebox: ShoeboxServiceClient,
    protected val db: Database,
    notificationRouter: NotificationRouter,
    clock: Clock,
    uriNormalizationUpdater: UriNormalizationUpdater,
    urbanAirship: UrbanAirship)
  extends ElizaServiceController with MessagingIndexHelper with Logging {

  //for indexing data requests
  def getThreadContentForIndexing(sequenceNumber: Long, maxBatchSize: Long) = Action { request =>
    Async(getThreadContentsForMessagesFromIdToId(Id[Message](sequenceNumber), Id[Message](sequenceNumber+maxBatchSize)).map{ threadContents =>
      Ok(JsArray(threadContents.map(Json.toJson(_))))
    })
  }

  //migration code
  private def recoverNotification(userId: Id[User], thread: MessageThread, messages: Seq[Message], id2BasicUser: Map[Id[User], BasicUser])(implicit session: RWSession) : Unit = {
    messages.collectFirst { case m if m.from.isDefined && m.from.get != userId => m }.map { lastMsgFromOther =>
      val locator = "/messages/" + thread.externalId

      val participantSet = thread.participants.map(_.participants.keySet).getOrElse(Set())
      val messageWithBasicUser = MessageWithBasicUser(
        lastMsgFromOther.externalId,
        lastMsgFromOther.createdAt,
        lastMsgFromOther.messageText,
        None,
        lastMsgFromOther.sentOnUrl.getOrElse(""),
        thread.nUrl.getOrElse(""),
        lastMsgFromOther.from.map(id2BasicUser(_)),
        participantSet.toSeq.map(id2BasicUser(_))
      )

      val notifJson = buildMessageNotificationJson(lastMsgFromOther, thread, messageWithBasicUser, locator, false)

      userThreadRepo.setNotification(userId, thread.id.get, lastMsgFromOther, notifJson, false)
      userThreadRepo.clearNotification(userId)
      userThreadRepo.setLastSeen(userId, thread.id.get, currentDateTime)
      userThreadRepo.setNotificationLastSeen(userId, currentDateTime)
    }
  }


  def importThread() = Action { request =>
    SafeFuture { shoebox.synchronized { //needed some arbitrary singleton object
      val req = request.body.asJson.get.asInstanceOf[JsObject]

      val uriId = Id[NormalizedURI]((req \ "uriId").as[Long])
      val participants = (req \ "participants").as[JsArray].value.map(v => Id[User](v.as[Long]))
      val extId = ExternalId[MessageThread]((req \ "extId").as[String])
      val messages = (req \ "messages").as[JsArray].value

      db.readWrite{ implicit session =>
        if (threadRepo.getOpt(extId).isEmpty) {
          log.info(s"MIGRATION: Importing thread $extId with participants $participants on uriid $uriId")
          val nUri = Await.result(shoebox.getNormalizedURI(uriId), 10 seconds) // todo: Remove await
          //create thread
          val mtps = MessageThreadParticipants(participants.toSet)
          val thread = threadRepo.save(MessageThread(
            id = None,
            externalId = extId,
            uriId = Some(uriId),
            url = Some(nUri.url),
            nUrl = Some(nUri.url),
            pageTitle = nUri.title,
            participants = Some(mtps),
            participantsHash = Some(mtps.hash),
            replyable = true
          ))

          //create userThreads
          participants.toSet.foreach{ userId : Id[User] =>
            userThreadRepo.create(userId, thread.id.get, Some(uriId))
          }

          val dbMessages = messages.sortBy( j => -1*(j \ "created_at").as[Long]).map{ messageJson =>
            val text = (messageJson \ "text").as[String]
            val from = Id[User]((messageJson \ "from").as[Long])
            val createdAt = (messageJson \ "created_at").as[DateTime]

            //create message
            messageRepo.save(Message(
              id= None,
              createdAt = createdAt,
              from = Some(from),
              thread = thread.id.get,
              threadExtId = thread.externalId,
              messageText = text,
              sentOnUrl = thread.url,
              sentOnUriId = Some(uriId)
            ))
          }

          log.info(s"MIGRATION: Starting notification recovery for $extId.")
          val participantSet = thread.participants.map(_.participants.keySet).getOrElse(Set())
          val id2BasicUser = Await.result(shoebox.getBasicUsers(participantSet.toSeq), 10 seconds) // todo: Remove await
          participants.toSet.foreach{ userId : Id[User] =>
            recoverNotification(userId, thread, dbMessages, id2BasicUser)
          }
          log.info(s"MIGRATION: Finished thread import for $extId")

        } else {
          log.info(s"MIGRATION: Thread $extId already imported. Doing nothing.")
        }
      }
    }}
    Ok("")
  }

  def sendGlobalNotification() = Action(parse.json) { request =>
    SafeFuture {
      val data : JsObject = request.body.asInstanceOf[JsObject]

      val userIds  : Set[Id[User]] =  (data \ "userIds").as[JsArray].value.map(v => v.asOpt[Long].map(Id[User](_))).flatten.toSet
      val title    : String        =  (data \ "title").as[String]
      val body     : String        =  (data \ "body").as[String]
      val linkText : String        =  (data \ "linkText").as[String]
      val linkUrl  : String        =  (data \ "linkUrl").as[String]
      val imageUrl : String        =  (data \ "imageUrl").as[String]
      val sticky   : Boolean       =  (data \ "sticky").as[Boolean]

      createGlobalNotificaiton(userIds, title, body, linkText, linkUrl, imageUrl, sticky)

    }
    Status(ACCEPTED)
  }

  def verifyAllNotifications() = Action { request => //Use with caution, very expensive!
    //Will need to change when we have detached threads.
    //currently only verifies
    SafeFuture{
      log.warn("Starting notification verification!")
      val userThreads : Seq[UserThread] = db.readOnly{ implicit session => userThreadRepo.all }
      val nUrls : Map[Id[MessageThread], Option[String]] = db.readOnly{ implicit session => threadRepo.all } map { thread => (thread.id.get, thread.url) } toMap

      userThreads.foreach{ userThread =>
        if (userThread.uriId.isDefined) {
          nUrls(userThread.thread).foreach{ correctNUrl =>
            log.warn(s"Verifying notification on user thread ${userThread.id.get}")
            uriNormalizationUpdater.fixLastNotificationJson(userThread, correctNUrl)
          }
        }
      }
    }
    Status(ACCEPTED)
  }

  def createGlobalNotificaiton(userIds: Set[Id[User]], title: String, body: String, linkText: String, linkUrl: String, imageUrl: String, sticky: Boolean) = {
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
        from = None,
        thread = thread.id.get,
        threadExtId = thread.externalId,
        messageText = s"$title (on $linkText): $body",
        sentOnUrl = Some(linkUrl),
        sentOnUriId = None
      ))

      (message, thread)
    }

    var errors : Set[Throwable] = Set[Throwable]()

    userIds.foreach{ userId =>
      try{
        val (notifJson, userThread) = db.readWrite{ implicit session =>
          val notifJson = Json.obj(
            "id"       -> message.externalId.id,
            "time"     -> message.createdAt,
            "thread"   -> message.threadExtId.id,
            "unread"   -> true,
            "category" -> "global",
            "title"    -> title,
            "bodyHtml" -> body,
            "linkText" -> linkText,
            "url"      -> linkUrl,
            "isSticky" -> sticky,
            "image"    -> imageUrl
          )

          val userThread = userThreadRepo.save(UserThread(
            id = None,
            user = userId,
            thread = thread.id.get,
            uriId = None,
            lastSeen = None,
            notificationPending = true,
            lastMsgFromOther = Some(message.id.get),
            lastNotification = notifJson,
            notificationUpdatedAt = message.createdAt,
            replyable = false
          ))

          (notifJson, userThread)
        }

        notificationRouter.sendToUser(
          userId,
          Json.arr("notification", notifJson)
        )
      } catch {
        case e: Throwable => errors = errors + e
      }
    }

    if (errors.size>0) throw scala.collection.parallel.CompositeThrowable(errors)
  }

  private[eliza] def buildThreadInfos(userId: Id[User], threads: Seq[MessageThread], requestUrl: Option[String]) : Seq[ElizaThreadInfo]  = {
    //get all involved users
    val allInvolvedUsers : Seq[Id[User]]= threads.flatMap{_.participants.map(_.all).getOrElse(Set())}
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
      val lastMessage = messagesByThread(thread.id.get).collectFirst { case m if m.from.isDefined => m }.get

      val messageTimes = messagesByThread(thread.id.get).take(10).map{ message =>
        (message.externalId, message.createdAt)
      }.toMap

      ElizaThreadInfo(
        externalId = thread.externalId,
        participants = thread.participants.map(_.all).getOrElse(Set()).map(userId2BasicUser(_)).toSeq,
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

  private def buildMessageNotificationJson(message: Message, thread: MessageThread, messageWithBasicUser: MessageWithBasicUser, locator: String, unread: Boolean) : JsValue = {
    Json.obj(
      "id"           -> message.externalId.id,
      "time"         -> message.createdAt,
      "thread"       -> thread.externalId.id,
      "text"         -> message.messageText,
      "url"          -> thread.nUrl,
      "title"        -> thread.pageTitle,
      "author"       -> messageWithBasicUser.user,
      "participants" -> messageWithBasicUser.participants,
      "locator"      -> locator,
      "unread"       -> unread,
      "category"     -> "message"
    )
  }

  def trimAtBytes(str: String, len: Int, charset: Charset) = { //Conner's Algorithm
    val outBuf = ByteBuffer.wrap(new Array[Byte](len))
    val inBuf = CharBuffer.wrap(str.toCharArray())
    charset.newEncoder().encode(inBuf, outBuf, true)
    new String(outBuf.array, 0, outBuf.position(), charset)
  }

  def sendPushNotification(userId:Id[User], extId:ExternalId[MessageThread], pendingNotificationCount:Int, msg:String) = {
    urbanAirship.notifyUser(userId, PushNotification(extId, pendingNotificationCount, msg))
  }

  private def sendNotificationForMessage(userId: Id[User], message: Message, thread: MessageThread, messageWithBasicUser: MessageWithBasicUser) : Unit = {
    SafeFuture {
      val locator = "/messages/" + thread.externalId

      val muted = db.readOnly { implicit session =>
        userThreadRepo.isMuted(userId, thread.id.get)
      }
      val notifJson = buildMessageNotificationJson(message, thread, messageWithBasicUser, locator, !muted)

      db.readWrite(attempts=2){ implicit session =>
        userThreadRepo.setNotification(userId, thread.id.get, message, notifJson, !muted)
      }

      shoebox.createDeepLink(message.from.get, userId, thread.uriId.get, DeepLocator(locator))

      notificationRouter.sendToUser(
        userId,
        Json.arr("notification", notifJson)
      )
      notificationRouter.sendToUser(userId, Json.arr("unread_notifications_count", getPendingNotificationCount(userId)))

      if (!muted) {
        val notifText = MessageLookHereRemover(messageWithBasicUser.user.map(_.firstName + ": ").getOrElse("") + message.messageText)
        sendPushNotification(userId, thread.externalId, getPendingNotificationCount(userId), trimAtBytes(notifText, 128, Charset.forName("UTF-8")))
      }
    }

    //This is mostly for testing and monitoring
    notificationRouter.sendNotification(Some(userId), Notification(thread.id.get, message.id.get))
  }

  val engineers = Seq(
    "ae5d159c-5935-4ad5-b979-ea280cb6c7ba", // eishay
    "dc6cb121-2a69-47c7-898b-bc2b9356054c", // andrew
    "772a9c0f-d083-44cb-87ce-de564cbbfa22", // yasu
    "d3cdb758-27df-4683-a589-e3f3d99fa47b", // jared
    "6d8e337d-4199-49e1-a95c-e4aab582eeca", // yinjgie
    "b80511f6-8248-4799-a17d-f86c1508c90d", // léo
    "597e6c13-5093-4cba-8acc-93318987d8ee", // stephen
    "147c5562-98b1-4fc1-946b-3873ac4a45b4", // eduardo
    "70927814-6a71-4eb4-85d4-a60164bae96c", // ray
    "9c211915-2413-4030-8efa-d7a9cfc77359"  // joon
  )
  val product = Seq (
    "3ad31932-f3f9-4fe3-855c-3359051212e5", // danny
    "1a316f42-13be-4d86-a4a2-8c7efb3010b8", // xander
    "2d18cd0b-ef30-4759-b6c5-f5f113a30f08", // effi
    "73b1134d-02d4-443f-b99b-e8bc571455e2", // chandler
    "c82b0fa0-6438-4892-8738-7fa2d96f1365", // ketan
    "41d57d50-0c14-45ae-8348-2200d70f9eb8", // van
    "ae139ae4-49ad-4026-b215-1ece236f1322"  // jen
  )
  val family = engineers ++ product ++ Seq(
    "e890b13a-e33c-4110-bd11-ddd51ec4eceb", // two-meals
    "6f21b520-87e7-4053-9676-85762e96970a"  // jenny
  )

  def constructRecipientSet(userExtIds: Seq[ExternalId[User]]) : Future[Set[Id[User]]] = {
    val loadedUser = userExtIds.map { userExtId =>
      userExtId match {
        case ExternalId("42424242-4242-4242-4242-424242424201") => // FortyTwo Engineering
          engineers.map(ExternalId[User])
        case ExternalId("42424242-4242-4242-4242-424242424202") => // FortyTwo Family
          family.map(ExternalId[User])
        case ExternalId("42424242-4242-4242-4242-424242424203") => // FortyTwo Product
          product.map(ExternalId[User])
        case notAGroup => Seq(notAGroup)
      }
    }
    shoebox.getUserIdsByExternalIds(loadedUser.flatten).map(_.toSet)
  }


  def sendNewMessage(from: Id[User], recipients: Set[Id[User]], urls: JsObject, titleOpt: Option[String], messageText: String) : (MessageThread, Message) = {
    val participants = recipients + from
    val urlOpt = (urls \ "url").asOpt[String]
    val tStart = currentDateTime
    val nUriOpt = urlOpt.map { url: String => Await.result(shoebox.internNormalizedURI(urls), 10 seconds)} // todo: Remove Await
    Statsd.timing(s"messaging.internNormalizedURI", currentDateTime.getMillis - tStart.getMillis)
    val uriIdOpt = nUriOpt.flatMap(_.id)
    val thread = db.readWrite{ implicit session =>
      val (thread, isNew) = threadRepo.getOrCreate(participants, urlOpt, uriIdOpt, nUriOpt.map(_.url), titleOpt.orElse(nUriOpt.flatMap(_.title)))
      if (isNew){
        log.info(s"This is a new thread. Creating User Threads.")
        participants.par.foreach{ userId =>
          userThreadRepo.create(userId, thread.id.get, uriIdOpt)
        }
      }
      else{
        log.info(s"Not actually a new thread. Merging.")
      }
      thread
    }
    sendMessage(from, thread, messageText, urlOpt, nUriOpt)

  }


  def sendMessage(from: Id[User], threadId: ExternalId[MessageThread], messageText: String, urlOpt: Option[String]): (MessageThread, Message) = {
    val thread = db.readOnly{ implicit session =>
      threadRepo.get(threadId)
    }
    sendMessage(from, thread, messageText, urlOpt)
  }

  def sendMessage(from: Id[User], threadId: Id[MessageThread], messageText: String, urlOpt: Option[String]): (MessageThread, Message) = {
    val thread = db.readOnly{ implicit session =>
      threadRepo.get(threadId)
    }
    sendMessage(from, thread, messageText, urlOpt)
  }


  def sendMessage(from: Id[User], thread: MessageThread, messageText: String, urlOpt: Option[String], nUriOpt: Option[NormalizedURI] = None) : (MessageThread, Message) = {
    if (! thread.containsUser(from) || !thread.replyable) throw NotAuthorizedException(s"User $from not authorized to send message on thread ${thread.id.get}")
    log.info(s"Sending message '$messageText' from $from to ${thread.participants}")
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
      setLastSeen(from, thread.id.get, Some(message.createdAt))
      db.readOnly { implicit session => messageRepo.refreshCache(thread.id.get) }
    }

    val participantSet = thread.participants.map(_.participants.keySet).getOrElse(Set())
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

    thread.participants.map(_.all.par.foreach { user =>
      notificationRouter.sendToUser(
        user,
        Json.arr("message", message.threadExtId.id, messageWithBasicUser)
      )
    })

    thread.allParticipantsExcept(from).foreach { userId =>
      sendNotificationForMessage(userId, message, thread, messageWithBasicUser)
    }

    //set notification json for message sender (if there isn't another yet)
    val isMuted = db.readOnly { implicit session => userThreadRepo.isMuted(from, thread.id.get) }
    val notifJson = buildMessageNotificationJson(message, thread, messageWithBasicUser, "/messages/" + thread.externalId, !isMuted)

    db.readWrite(attempts=2){ implicit session =>
      userThreadRepo.setNotificationJsonIfNotPresent(from, thread.id.get, notifJson, message)
    }

    //async update normalized url id so as not to block on that (the shoebox call yields a future)
    urlOpt.foreach { url =>
      (nUriOpt match {
        case Some(n) => Promise.successful(n).future
        case None => shoebox.internNormalizedURI(Json.obj("url" -> url)) //Note, this also needs to include canonical/og when we have detached threads
      }) foreach { nUri =>
        db.readWrite { implicit session =>
          messageRepo.updateUriId(message, nUri.id.get)
        }
      }
    }
    (thread, message)
  }


  def getThreadMessages(thread: MessageThread, pageOpt: Option[Int]) : Seq[Message] =
    db.readOnly { implicit session =>
      log.info(s"[get_thread] trying to get thread messages for thread extId ${thread.externalId}. pageOpt is $pageOpt")
      pageOpt.map { page =>
        val lower = MessagingController.THREAD_PAGE_SIZE*page
        val upper = MessagingController.THREAD_PAGE_SIZE*(page+1)-1
        log.info(s"[get_thread] getting thread messages for thread extId ${thread.externalId}. lu: $lower, $upper")
        messageRepo.get(thread.id.get,lower,Some(upper))
      } getOrElse {
        log.info(s"[get_thread] getting thread messages for thread extId ${thread.externalId}. no l/u")
        messageRepo.get(thread.id.get, 0, None)
      }
    }


  def getThreadMessages(threadExtId: ExternalId[MessageThread], pageOpt: Option[Int]) : Seq[Message] = {
    val thread = db.readOnly{ implicit session =>
      threadRepo.get(threadExtId)
    }
    getThreadMessages(thread, pageOpt)
  }

  def getThreadMessages(threadId: Id[MessageThread], pageOpt: Option[Int]): Seq[Message] = {
    getThreadMessages(db.readOnly(threadRepo.get(threadId)(_)), pageOpt)
  }

  def participantsToBasicUsers(participants: MessageThreadParticipants): Future[Map[Id[User], BasicUser]] =
    shoebox.getBasicUsers(participants.participants.keySet.toSeq)


  def getThreadMessagesWithBasicUser(thread: MessageThread, pageOpt: Option[Int]): Future[(MessageThread, Seq[MessageWithBasicUser])] = {
    val participantSet = thread.participants.map(_.participants.keySet).getOrElse(Set())
    log.info(s"[get_thread] got participants for extId ${thread.externalId}: $participantSet")
    shoebox.getBasicUsers(participantSet.toSeq) map { id2BasicUser =>
      val messages = getThreadMessages(thread, pageOpt)
      log.info(s"[get_thread] got raw messages for extId ${thread.externalId}: $messages")
      (thread, messages.map { message =>
        MessageWithBasicUser(
          id           = message.externalId,
          createdAt    = message.createdAt,
          text         = message.messageText,
          auxData      = message.auxData,
          url          = message.sentOnUrl.getOrElse(""),
          nUrl         = thread.nUrl.getOrElse(""), //TODO Stephen: This needs to change when we have detached threads
          user         = message.from.map(id2BasicUser(_)),
          participants = participantSet.toSeq.map(id2BasicUser(_))
        )
      })
    }

  }

  def getThreadMessagesWithBasicUser(threadExtId: ExternalId[MessageThread], pageOpt: Option[Int]): Future[(MessageThread, Seq[MessageWithBasicUser])] = {
    val thread = db.readOnly(threadRepo.get(threadExtId)(_))
    getThreadMessagesWithBasicUser(thread, pageOpt)
  }

  def getThread(threadExtId: ExternalId[MessageThread]) : MessageThread = {
    db.readOnly{ implicit session =>
      threadRepo.get(threadExtId)
    }
  }

  def getThreads(user: Id[User], url: Option[String]=None): Seq[MessageThread] = {
    db.readOnly { implicit session =>
      val threadIds = userThreadRepo.getThreads(user)
      threadIds.map(threadRepo.get(_))
    }
  }

  def addParticipantsToThread(adderUserId: Id[User], threadExtId: ExternalId[MessageThread], newParticipants: Seq[ExternalId[User]]) = {
    shoebox.getUserIdsByExternalIds(newParticipants) map { newParticipantsUserIds =>

      val messageThreadOpt = db.readWrite { implicit session =>
        val oldThread = threadRepo.get(threadExtId)
        val actuallyNewParticipantUserIds = newParticipantsUserIds.filterNot(oldThread.containsUser)

        if (!oldThread.participants.exists(_.contains(adderUserId)) || actuallyNewParticipantUserIds.isEmpty) {
          None
        } else {
          val thread = threadRepo.save(oldThread.withParticipants(clock.now, actuallyNewParticipantUserIds: _*))

          val message = messageRepo.save(Message(
            from = None,
            thread = thread.id.get,
            threadExtId = thread.externalId,
            messageText = "",
            auxData = Some(Json.arr("add_participants", adderUserId.id.toString, actuallyNewParticipantUserIds.map(_.id))),
            sentOnUrl = None,
            sentOnUriId = None
          ))
          SafeFuture { db.readOnly { implicit session => messageRepo.refreshCache(thread.id.get) } }
          Some((actuallyNewParticipantUserIds, message, thread))
        }
      }

      messageThreadOpt.exists { case (newParticipants, message, thread) =>
        shoebox.getBasicUsers(thread.participants.get.participants.keys.toSeq) map { basicUsers =>

          val adderName = basicUsers.get(adderUserId).map(n => n.firstName + " " + n.lastName).get

          val adderUserName = basicUsers(adderUserId).firstName + " " + basicUsers(adderUserId).lastName
          val theTitle: String = thread.pageTitle.getOrElse("New conversation")
          val notificationJson = Json.obj(
            "id"           -> message.externalId.id,
            "time"         -> message.createdAt,
            "thread"       -> thread.externalId.id,
            "text"         -> s"$adderUserName added you to a conversation.",
            "url"          -> thread.nUrl,
            "title"        -> theTitle,
            "author"       -> basicUsers(adderUserId),
            "participants" -> basicUsers.values.toSeq,
            "locator"      -> ("/messages/" + thread.externalId),
            "unread"       -> true,
            "category"     -> "message"
          )
          db.readWrite { implicit session =>
            newParticipants.map { pUserId =>
              userThreadRepo.save(UserThread(
                id = None,
                user = pUserId,
                thread = thread.id.get,
                uriId = thread.uriId,
                lastSeen = None,
                notificationPending = true,
                lastMsgFromOther = Some(message.id.get),
                lastNotification = notificationJson,
                notificationUpdatedAt = message.createdAt,
                replyable = false
              ))
              notificationRouter.sendToUser(
                pUserId,
                Json.arr("notification", notificationJson)
              )
            }
          }

          val participants = basicUsers.values.toSeq
          val mwbu = MessageWithBasicUser(message.externalId, message.createdAt, "", message.auxData, "", "", None, participants)
          modifyMessageWithAuxData(mwbu).map { augmentedMessage =>
            thread.participants.map(_.all.par.foreach { userId =>
              notificationRouter.sendToUser(
                userId,
                Json.arr("message", thread.externalId.id, augmentedMessage)
              )
              notificationRouter.sendToUser(
                userId,
                Json.arr("thread_participants", thread.externalId.id, participants)
              )
            })
          }
        }
        true
      }
    }
  }

  def modifyMessageWithAuxData(m: MessageWithBasicUser): Future[MessageWithBasicUser] = {

    if (m.user.isEmpty) {
      val modifiedMessage = m.auxData match {
        case Some(auxData) =>
          val auxModifiedFuture = auxData.value match {
            case JsString("add_participants") +: JsString(jsAdderUserId) +: JsArray(jsAddedUsers) +: _ =>
              val addedUsers = jsAddedUsers.map(id => Id[User](id.as[Long]))
              val adderUserId = Id[User](jsAdderUserId.toLong)
              val basicUsers = m.participants
              shoebox.getBasicUsers(adderUserId +: addedUsers) map { basicUsers =>
                val adderUser = basicUsers(adderUserId)
                val addedBasicUsers = addedUsers.map(u => basicUsers(u))
                val addedUsersString = addedBasicUsers.map(s => s"${s.firstName} ${s.lastName}") match {
                  case first :: Nil => first
                  case first :: second :: Nil => first + " and " + second
                  case many => many.take(many.length - 1).mkString(", ") + ", and " + many.last
                }

                val friendlyMessage = s"${adderUser.firstName} ${adderUser.lastName} added $addedUsersString to the conversation."
                (friendlyMessage, Json.arr("add_participants", basicUsers(adderUserId), addedBasicUsers))
              }
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


  def setNotificationRead(userId: Id[User], threadId: Id[MessageThread]): Unit = {
    db.readWrite(attempts=2){implicit session => userThreadRepo.clearNotification(userId, Some(threadId))}
  }


  def setAllNotificationsRead(userId: Id[User]): Unit = {
    log.info(s"Setting all Notifications as read for user $userId.")
    db.readWrite(attempts=2){implicit session => userThreadRepo.clearNotification(userId)}
  }

  def setAllNotificationsReadBefore(user: Id[User], messageId: ExternalId[Message]) : DateTime = {
    val lastTime = db.readWrite(attempts=2){ implicit session =>
      val message = messageRepo.get(messageId)
      userThreadRepo.clearNotificationsBefore(user, message.createdAt)
      message.createdAt
    }
    notificationRouter.sendToUser(user, Json.arr("unread_notifications_count", getPendingNotificationCount(user)))
    lastTime
  }

  def setLastSeen(userId: Id[User], threadId: Id[MessageThread], timestampOpt: Option[DateTime] = None) : Unit = {
    db.readWrite{ implicit session =>
      userThreadRepo.setLastSeen(userId, threadId, timestampOpt.getOrElse(clock.now))
    }
  }

  def setLastSeen(userId: Id[User], messageExtId: ExternalId[Message]) : Unit = {
    val message = db.readOnly{ implicit session => messageRepo.get(messageExtId) }
    setLastSeen(userId, message.thread, Some(message.createdAt))
  }

  def getPendingNotifications(userId: Id[User]) : Seq[Notification] = {
    db.readOnly{ implicit session =>
      userThreadRepo.getPendingNotifications(userId)
    }
  }

  def setNotificationLastSeen(userId: Id[User], timestamp: DateTime) : Unit = {
    db.readWrite(attempts=2){ implicit session =>
      userThreadRepo.setNotificationLastSeen(userId, timestamp)
    }
  }

  def getNotificationLastSeen(userId: Id[User]): Option[DateTime] = {
    db.readOnly{ implicit session =>
      userThreadRepo.getNotificationLastSeen(userId)
    }
  }

  def getLatestSendableNotifications(userId: Id[User], howMany: Int): Seq[JsObject] = {
    db.readOnly{ implicit session =>
      userThreadRepo.getLatestSendableNotifications(userId, howMany)
    }
  }

  def getLatestUnreadSendableNotifications(userId: Id[User], howMany: Int): Seq[JsObject] = {
    db.readOnly{ implicit session =>
      userThreadRepo.getLatestUnreadSendableNotifications(userId, howMany)
    }
  }

  def getLatestMutedSendableNotifications(userId: Id[User], howMany: Int): Seq[JsObject] = {
    db.readOnly{ implicit session =>
      userThreadRepo.getLatestMutedSendableNotifications(userId, howMany)
    }
  }

  def getLatestSentNotifications(userId: Id[User], howMany: Int): Seq[JsObject] = { //ZZZ Need to backpolutate the json on threads with only one active user
    val activeThreads : Seq[Id[MessageThread]] = getActiveThreadsForUser(userId)
    val notifJson : Seq[JsObject] = db.readOnly{ implicit session => userThreadRepo.getLatestSendableNotificationsForThreads(userId, activeThreads, howMany) }
    notifJson
  }

  def getPendingNotificationCount(userId: Id[User]): Int = {
    db.readOnly{ implicit session =>
      userThreadRepo.getPendingNotificationCount(userId)
    }
  }

  def getSendableNotificationsAfter(userId: Id[User], after: DateTime): Seq[JsObject] = {
    db.readOnly{ implicit session =>
      userThreadRepo.getSendableNotificationsAfter(userId, after)
    }
  }

  def getSendableNotificationsBefore(userId: Id[User], after: DateTime, howMany: Int): Seq[JsObject] = {
    db.readOnly{ implicit session =>
      userThreadRepo.getSendableNotificationsBefore(userId, after, howMany)
    }
  }

  def getThreadInfo(userId: Id[User], threadExtId: ExternalId[MessageThread]): ElizaThreadInfo = {
    val thread = db.readOnly { implicit session =>
      threadRepo.get(threadExtId)
    }
    buildThreadInfos(userId, Seq(thread), None).head
  }

  def getThreadInfos(userId: Id[User], url: String): (String, Seq[ElizaThreadInfo]) = {
    val nUriOrPrenorm = Await.result(shoebox.getNormalizedUriByUrlOrPrenormalize(url), 2 seconds) // todo: Remove await
    val (nUrlStr, unsortedInfos) = if (nUriOrPrenorm.isLeft) {
      val nUri = nUriOrPrenorm.left.get
      val threads = db.readOnly { implicit session =>
        val threadIds = userThreadRepo.getThreads(userId, nUri.id)
        threadIds.map(threadRepo.get)
      }.filter(_.replyable)
      (nUri.url, buildThreadInfos(userId, threads, Some(url)))
    } else {
      (nUriOrPrenorm.right.get, Seq[ElizaThreadInfo]())
    }
    val infos = unsortedInfos sortWith { (a,b) =>
      a.lastCommentedAt.compareTo(b.lastCommentedAt) < 0
    }
    (nUrlStr, infos)
  }

  def muteThread(userId: Id[User], threadId: ExternalId[MessageThread]): Boolean = {
    setUserThreadMuteState(userId, threadId, true) tap { stateChanged =>
      if(stateChanged) { notifyUserAboutMuteChange(userId, threadId, true) }
    }
  }

  def unmuteThread(userId: Id[User], threadId: ExternalId[MessageThread]): Boolean = {
    setUserThreadMuteState(userId, threadId, false) tap { stateChanged =>
      if(stateChanged) { notifyUserAboutMuteChange(userId, threadId, false) }
    }
  }

  private def setUserThreadMuteState(userId: Id[User], threadId: ExternalId[MessageThread], mute: Boolean) = {
    db.readWrite { implicit session =>
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
  }

  private def notifyUserAboutMuteChange(userId: Id[User], threadId: ExternalId[MessageThread], mute: Boolean) = {
    notificationRouter.sendToUser(userId, Json.arr("thread_muted", threadId.id, mute))
  }

  def getChatter(userId: Id[User], urls: Seq[String]) = {
    implicit val timeout = Duration(3, "seconds")
    TimeoutFuture(Future.sequence(urls.map(u => shoebox.getNormalizedURIByURL(u).map(u -> _)))).recover {
      case ex: TimeoutException => Seq[(String, Option[NormalizedURI])]()
    }.map { res =>
      val urlMsgCount = db.readOnly { implicit session =>
        res.filter(_._2.isDefined).map { case (url, nuri) =>
          url -> userThreadRepo.getThreads(userId, Some(nuri.get.id.get))
        }
      }
      Map(urlMsgCount: _*)
    }
  }

  def connectedSockets: Int  = notificationRouter.connectedSockets

  def setNotificationReadForMessage(userId: Id[User], msgExtId: ExternalId[Message]): Unit = {
    val message = db.readOnly{ implicit session => messageRepo.get(msgExtId) }
    val thread  = db.readOnly{ implicit session => threadRepo.get(message.thread) } //TODO: This needs to change when we have detached threads
    val nUrl: String = thread.nUrl.getOrElse("")
    if (message.from.isEmpty || message.from.get != userId) {
      db.readWrite(attempts=2) { implicit session =>
        userThreadRepo.clearNotificationForMessage(userId, thread.id.get, message)
      }
    }
    notificationRouter.sendToUser(userId, Json.arr("message_read", nUrl, message.threadExtId.id, message.createdAt, message.externalId.id))
    notificationRouter.sendToUser(userId, Json.arr("unread_notifications_count", getPendingNotificationCount(userId)))
  }

  def setNotificationUnread(userId: Id[User], threadExtId: ExternalId[MessageThread]) : Unit = {
    val thread = db.readOnly{ implicit session =>
      threadRepo.get(threadExtId)
    }
    db.readWrite{ implicit session =>
      userThreadRepo.markPending(userId, thread.id.get)
    }
    notificationRouter.sendToUser(userId, Json.arr("set_notification_unread", threadExtId.id))
    notificationRouter.sendToUser(userId, Json.arr("unread_notifications_count", getPendingNotificationCount(userId)))
  }

  def getActiveThreadsForUser(userId: Id[User]): Seq[Id[MessageThread]] = {
    db.readOnly{ implicit session => messageRepo.getActiveThreadsForUser(userId) }
  }


}

