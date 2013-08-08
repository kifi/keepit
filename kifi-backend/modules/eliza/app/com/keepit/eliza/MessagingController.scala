package com.keepit.eliza

import com.keepit.model.{User}
import com.keepit.common.db.{Id, ExternalId}
import com.keepit.common.db.slick.{Database}
import com.keepit.shoebox.{ShoeboxServiceClient}
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.social.BasicUser

import scala.concurrent.{future, Await, Future}
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

import com.google.inject.Inject

import org.joda.time.DateTime

import play.api.libs.json.{JsValue, JsNull, Json}

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
    clock: Clock
    )
  extends Logging {


  private def buildThreadInfos(userId: Id[User], threads: Seq[MessageThread]) : Seq[ElizaThreadInfo]  = {
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

      val messageTimes = messagesByThread(thread.id.get).map{ message =>
        (message.externalId, message.createdAt)
      }.toMap

      ElizaThreadInfo(
        externalId=thread.externalId,
        recipients=thread.participants.map(_.all).getOrElse(Set()).map(userId2BasicUser(_)).toSeq,
        digest= lastMessage.messageText,
        lastAuthor=userId2BasicUser(lastMessage.from.get).externalId,
        messageCount=messagesByThread(thread.id.get).length,
        messageTimes=messageTimes,
        createdAt=thread.createdAt,
        lastCommentedAt= lastMessage.createdAt,
        lastMessageRead=userThreads(thread.id.get).lastSeen,
        nUrl = thread.nUrl.getOrElse("")
      )

    }

  }


  private def sendNotificationForMessage(user: Id[User], message: Message, thread: MessageThread, messageWithBasicUser: MessageWithBasicUser) : Unit = { //TODO Stephen: Construct and store notification json
    future {
      db.readWrite{ implicit session => 
        userThreadRepo.setNotification(user, thread.id.get)
      }
    }

    notificationRouter.sendToUser(
      user,
      Json.arr("message", message.threadExtId.id, messageWithBasicUser)
    )

    //This is mostly for testing and monitoring
    notificationRouter.sendNotification(Some(user), Notification(thread.id.get, message.id.get))
  }

  def constructRecipientSet(userExtIds: Seq[ExternalId[User]]) : Future[Set[Id[User]]] = {
    shoebox.getUserIdsByExternalIds(userExtIds).map(_.toSet)
  }


  def sendNewMessage(from: Id[User], recipients: Set[Id[User]], urlOpt: Option[String], messageText: String) : Message = {
    val participants = recipients + from
    val nUriOpt = urlOpt.map{url: String => Await.result(shoebox.normalizeURL(url), 10 seconds)}
    val uriIdOpt = nUriOpt.flatMap(_.id)
    val thread = db.readWrite{ implicit session => 
      val (thread, isNew) = threadRepo.getOrCreate(participants, urlOpt, uriIdOpt, nUriOpt.map(_.url)) 
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
    sendMessage(from, thread, messageText, urlOpt)
    
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


  def sendMessage(from: Id[User], thread: MessageThread, messageText: String, urlOpt: Option[String]) : Message = {
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
    val id2BasicUser = Await.result(shoebox.getBasicUsers(participantSet.toSeq), 1 seconds)
    
    val messageWithBasicUser = MessageWithBasicUser(
      message.externalId,
      message.createdAt,
      message.messageText,
      message.sentOnUrl.getOrElse(""),
      thread.nUrl.getOrElse(""), //TODO Stephen: This needs to change when we have detached threads
      message.from.map(id2BasicUser(_)),
      message.from.map(participantSet - _).getOrElse(participantSet).toSeq.map(id2BasicUser(_))
    )

    thread.allUsersExcept(from).foreach { userId =>
      db.readWrite{ implicit session => userThreadRepo.setLastMsgFromOther(userId, thread.id.get, message.id.get) }
      sendNotificationForMessage(userId, message, thread, messageWithBasicUser)
    }
    //async update normalized url id so as not to block on that (the shoebox call yields a future)
    urlOpt.foreach{ url =>
      shoebox.normalizeURL(url).foreach{ nUri =>
        db.readWrite { implicit session => 
          messageRepo.updateUriId(message, nUri.id.get)
        }
      }
    }
    message
  }


  def getThreadMessages(thread: MessageThread, pageOpt: Option[Int]) : Seq[Message] = 
    db.readOnly {implicit sesstion => 
      pageOpt.map { page =>
        val lower = MessagingController.THREAD_PAGE_SIZE*page
        val upper = MessagingController.THREAD_PAGE_SIZE*(page+1)-1
        messageRepo.get(thread.id.get,lower,Some(upper)) 
      } getOrElse {
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

  def getThreadMessagesWithBasicUser(threadExtId: ExternalId[MessageThread], pageOpt: Option[Int]) : Seq[MessageWithBasicUser] = {
    val thread = db.readOnly{ implicit session =>
      threadRepo.get(threadExtId)
    }
    val participantSet = thread.participants.map(_.participants.keySet).getOrElse(Set())
    val id2BasicUser = Await.result(shoebox.getBasicUsers(participantSet.toSeq), 1 seconds)
    val messages = getThreadMessages(thread, pageOpt)
    messages.map{ message =>
      MessageWithBasicUser(
        message.externalId,
        message.createdAt,
        message.messageText,
        message.sentOnUrl.getOrElse(""),
        thread.nUrl.getOrElse(""), //TODO Stephen: This needs to change when we have detached threads
        message.from.map(id2BasicUser(_)),
        message.from.map(participantSet - _).getOrElse(participantSet).toSeq.map(id2BasicUser(_))
      )
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

  def getLatestSendableNotifications(userId: Id[User], howMany: Int): Seq[JsValue] = {
    db.readOnly{ implicit session =>
      userThreadRepo.getLatestSendableNotifications(userId, howMany).filter(_!=null) //Workaraound for Json serialization bug
    }
  }

  def getPendingNotificationCount(userId: Id[User]): Int = {
    db.readOnly{ implicit session =>
      userThreadRepo.getPendingNotificationCount(userId)
    }
  }

  def getSendableNotificationsAfter(userId: Id[User], after: DateTime): Seq[JsValue] = {
    db.readOnly{ implicit session =>
      userThreadRepo.getSendableNotificationsAfter(userId, after)
      userThreadRepo.getSendableNotificationsAfter(userId, after).filter(_!=null) //Workaraound for Json serialization bug
    }
  }

  def getSendableNotificationsBefore(userId: Id[User], after: DateTime, howMany: Int): Seq[JsValue] = {
    db.readOnly{ implicit session =>
      userThreadRepo.getSendableNotificationsBefore(userId, after, howMany)
      userThreadRepo.getSendableNotificationsBefore(userId, after, howMany).filter(_!=null) //Workaraound for Json serialization bug
    }
  }

  def getThreadInfos(userId: Id[User], url: String): Seq[ElizaThreadInfo] = {
    val uriId = Await.result(shoebox.normalizeURL(url), 2 seconds).id.get
    val threads = db.readOnly { implicit session =>
      val threadIds = userThreadRepo.getThreads(userId, Some(uriId))
      threadIds.map(threadRepo.get(_))
    }
    buildThreadInfos(userId, threads)
  }

  def connectedSockets: Int  = notificationRouter.connectedSockets


}

