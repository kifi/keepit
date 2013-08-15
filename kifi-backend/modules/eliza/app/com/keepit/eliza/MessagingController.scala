package com.keepit.eliza

import com.keepit.model.{User, DeepLocator,NormalizedURI}
import com.keepit.common.db.{Id, ExternalId}
import com.keepit.common.db.slick.{Database}
import com.keepit.shoebox.{ShoeboxServiceClient}
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.social.BasicUser
import com.keepit.common.controller.ElizaServiceController

import scala.concurrent.{Promise, future, Await, Future}
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

import com.google.inject.Inject

import org.joda.time.DateTime

import play.api.libs.json.{JsValue, JsNull, Json, JsObject, JsArray}


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
    threadRepo: MessageThreadRepo, 
    userThreadRepo: UserThreadRepo, 
    messageRepo: MessageRepo, 
    shoebox: ShoeboxServiceClient, 
    db: Database,
    notificationRouter: NotificationRouter,
    clock: Clock,
    uriNormalizationUpdater: UriNormalizationUpdater
    )
  extends ElizaServiceController with Logging {



  //migration code
  private def recoverNotification(userId: Id[User], thread: MessageThread, messages: Seq[Message], id2BasicUser: Map[Id[User], BasicUser])(implicit session: RWSession) : Unit = {

    messages.filter(_.from.map(_!=userId).getOrElse(true)).headOption.foreach{ lastMsgFromOther =>

      val locator = "/messages/" + thread.externalId

      val participantSet = thread.participants.map(_.participants.keySet).getOrElse(Set())
      val messageWithBasicUser = MessageWithBasicUser(
        lastMsgFromOther.externalId,
        lastMsgFromOther.createdAt,
        lastMsgFromOther.messageText,
        lastMsgFromOther.sentOnUrl.getOrElse(""),
        thread.nUrl.getOrElse(""),
        lastMsgFromOther.from.map(id2BasicUser(_)),
        participantSet.toSeq.map(id2BasicUser(_))
      )

      val notifJson = buildMessageNotificationJson(lastMsgFromOther, thread, messageWithBasicUser, locator) 

      userThreadRepo.setNotification(userId, thread.id.get, lastMsgFromOther.id.get, notifJson)
      userThreadRepo.clearNotification(userId)
      userThreadRepo.setLastSeen(userId, thread.id.get, currentDateTime(zones.PT))
      userThreadRepo.setNotificationLastSeen(userId, currentDateTime(zones.PT))
    }

  }


  def importThread() = Action { request =>
    future { shoebox.synchronized { //needed some arbitrary singleton object
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


  private def buildThreadInfos(userId: Id[User], threads: Seq[MessageThread], requestUrl: String) : Seq[ElizaThreadInfo]  = {
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
      val lastMessage = messagesByThread(thread.id.get).head

      val messageTimes = messagesByThread(thread.id.get).take(10).map{ message =>
        (message.externalId, message.createdAt)
      }.toMap

      ElizaThreadInfo(
        externalId=thread.externalId,
        participants=thread.participants.map(_.all).getOrElse(Set()).map(userId2BasicUser(_)).toSeq,
        digest= lastMessage.messageText,
        lastAuthor=userId2BasicUser(lastMessage.from.get).externalId,
        messageCount=messagesByThread(thread.id.get).length,
        messageTimes=messageTimes,
        createdAt=thread.createdAt,
        lastCommentedAt=lastMessage.createdAt,
        lastMessageRead=userThreads(thread.id.get).lastSeen,
        nUrl = thread.nUrl.getOrElse(""),
        url = requestUrl
      )

    }

  }

  private def buildMessageNotificationJson(message: Message, thread: MessageThread, messageWithBasicUser: MessageWithBasicUser, locator: String) : JsValue = {
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
      "unread"       -> true
    ) 
  }

  private def sendNotificationForMessage(user: Id[User], message: Message, thread: MessageThread, messageWithBasicUser: MessageWithBasicUser) : Unit = { //TODO Stephen: And store notification json
    future {
      val locator = "/messages/" + thread.externalId
      val notifJson = buildMessageNotificationJson(message, thread, messageWithBasicUser, locator)

      db.readWrite{ implicit session => 
        userThreadRepo.setNotification(user, thread.id.get, message.id.get, notifJson)
      }
      
      notificationRouter.sendToUser(
        user,
        Json.arr("notification", notifJson)
      )

      shoebox.createDeepLink(message.from.get, user, thread.uriId.get, DeepLocator(locator))

    }

    future{
      shoebox.sendPushNotification(user, message.externalId.id, getPendingNotificationCount(user), messageWithBasicUser.user.map(_.firstName + ": ").getOrElse("") + message.messageText)
    }

    //This is mostly for testing and monitoring
    notificationRouter.sendNotification(Some(user), Notification(thread.id.get, message.id.get))
  }

  def constructRecipientSet(userExtIds: Seq[ExternalId[User]]) : Future[Set[Id[User]]] = {
    shoebox.getUserIdsByExternalIds(userExtIds).map(_.toSet)
  }


  def sendNewMessage(from: Id[User], recipients: Set[Id[User]], urlOpt: Option[String], messageText: String) : Message = {
    val participants = recipients + from
    val nUriOpt = urlOpt.map { url: String => Await.result(shoebox.normalizeURL(url), 10 seconds)} // todo: Remove Await
    val uriIdOpt = nUriOpt.flatMap(_.id)
    val thread = db.readWrite{ implicit session => 
      val (thread, isNew) = threadRepo.getOrCreate(participants, urlOpt, uriIdOpt, nUriOpt.map(_.url), nUriOpt.flatMap(_.title)) 
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


  def sendMessage(from: Id[User], threadId: ExternalId[MessageThread], messageText: String, urlOpt: Option[String]): Message = {
    val thread = db.readOnly{ implicit session =>
      threadRepo.get(threadId)
    }
    sendMessage(from, thread, messageText, urlOpt)
  }

  def sendMessage(from: Id[User], threadId: Id[MessageThread], messageText: String, urlOpt: Option[String]): Message = {
    val thread = db.readOnly{ implicit session =>
      threadRepo.get(threadId)
    }
    sendMessage(from, thread, messageText, urlOpt)
  }


  def sendMessage(from: Id[User], thread: MessageThread, messageText: String, urlOpt: Option[String], nUriOpt: Option[NormalizedURI] = None) : Message = {
    if (! thread.containsUser(from)) throw NotAuthorizedException(s"User $from not authorized to send message on thread ${thread.id.get}")
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

    val participantSet = thread.participants.map(_.participants.keySet).getOrElse(Set())
    val id2BasicUser = Await.result(shoebox.getBasicUsers(participantSet.toSeq), 1 seconds) // todo: remove await
    
    val messageWithBasicUser = MessageWithBasicUser(
      message.externalId,
      message.createdAt,
      message.messageText,
      message.sentOnUrl.getOrElse(""),
      thread.nUrl.getOrElse(""), //TODO Stephen: This needs to change when we have detached threads
      message.from.map(id2BasicUser(_)),
      participantSet.toSeq.map(id2BasicUser(_))
    )

    thread.participants.map(_.all.foreach { user =>
      notificationRouter.sendToUser(
        user,
        Json.arr("message", message.threadExtId.id, messageWithBasicUser)
      )
    })             

    thread.allUsersExcept(from).foreach { userId =>
      sendNotificationForMessage(userId, message, thread, messageWithBasicUser)
    }
    //async update normalized url id so as not to block on that (the shoebox call yields a future)
    urlOpt.foreach { url =>
      (nUriOpt match {
        case Some(n) => Promise.successful(n).future
        case None => shoebox.normalizeURL(url)
      }) foreach { nUri =>
        db.readWrite { implicit session => 
          messageRepo.updateUriId(message, nUri.id.get)
        }
      }
    }
    message
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

  def getThreadMessages(threadId: Id[MessageThread], pageOpt: Option[Int]) : Seq[Message] = {
    val thread = db.readOnly{ implicit session =>
      threadRepo.get(threadId)
    }
    getThreadMessages(thread, pageOpt)
  }

  def getThreadMessagesWithBasicUser(threadExtId: ExternalId[MessageThread], pageOpt: Option[Int]): Future[Seq[MessageWithBasicUser]] = {
    val thread = db.readOnly{ implicit session =>
      threadRepo.get(threadExtId)
    }
    log.info(s"[get_thread] got thread for extId $threadExtId: $thread")
    val participantSet = thread.participants.map(_.participants.keySet).getOrElse(Set())
    log.info(s"[get_thread] got participants for extId $threadExtId: $participantSet")
    shoebox.getBasicUsers(participantSet.toSeq) map { id2BasicUser =>
      val messages = getThreadMessages(thread, pageOpt)
      log.info(s"[get_thread] got raw messages for extId $threadExtId: $messages")
      messages.map { message =>
        MessageWithBasicUser(
          message.externalId,
          message.createdAt,
          message.messageText,
          message.sentOnUrl.getOrElse(""),
          thread.nUrl.getOrElse(""), //TODO Stephen: This needs to change when we have detached threads
          message.from.map(id2BasicUser(_)),
          participantSet.toSeq.map(id2BasicUser(_))
        )
      }
    }
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


  def setNotificationRead(userId: Id[User], threadId: Id[MessageThread]): Unit = {
    db.readWrite{implicit session => userThreadRepo.clearNotification(userId, Some(threadId))}
  }


  def setAllNotificationsRead(userId: Id[User]): Unit = {
    log.info("Setting all Notifications as read for user $userId.")
    db.readWrite{implicit session => userThreadRepo.clearNotification(userId)}
  }

  def setAllNotificationsReadBefore(user: Id[User], messageId: ExternalId[Message]) : DateTime = {
    db.readWrite{ implicit session =>
      val message = messageRepo.get(messageId)
      userThreadRepo.clearNotificationsBefore(user, message.createdAt)
      message.createdAt
    }
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
    db.readWrite{ implicit session =>
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

  def getThreadInfos(userId: Id[User], url: String): Seq[ElizaThreadInfo] = {
    val uriId = Await.result(shoebox.normalizeURL(url), 2 seconds).id.get // todo: Remove await
    val threads = db.readOnly { implicit session =>
      val threadIds = userThreadRepo.getThreads(userId, Some(uriId))
      threadIds.map(threadRepo.get(_))
    }
    buildThreadInfos(userId, threads, url)
  }

  def connectedSockets: Int  = notificationRouter.connectedSockets

  def setNotificationReadForMessage(userId: Id[User], msgExtId: ExternalId[Message]) : Unit = {
    val message = db.readOnly{ implicit session => messageRepo.get(msgExtId) }
    val thread  = db.readOnly{ implicit session => threadRepo.get(message.thread) } //TODO: This needs to change when we have detached threads
    val nUrl : String = thread.nUrl.getOrElse("")
    db.readWrite{ implicit session => userThreadRepo.clearNotificationForMessage(userId, thread.id.get, message.id.get) }
    notificationRouter.sendToUser(userId, Json.arr("message_read", nUrl, message.threadExtId.id, message.createdAt, message.externalId.id))
  }


}

