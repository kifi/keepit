package com.keepit.bender

import com.keepit.model.{User}
import com.keepit.common.db.{Id, ExternalId}
import com.keepit.common.db.slick.{Database}
import com.keepit.shoebox.{ShoeboxServiceClient}
import com.keepit.common.logging.Logging

import scala.concurrent.{future, Await}
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

import play.api.libs.json.{JsValue, JsNull}

/* To future maintainers 
*  If this is ever getting too slow the first things I would look at:
*  -External Id lookups. Make those numeric Id (instead of giant strings) and index them in the db
*  -Synchronous dependency on normalized url id in thread creation. Remove it.
*  -Go to 64 bit for the participants hash
*  -Use "Insert Ignore" where possible
*/


case class NotAuthorizedException() extends java.lang.Throwable

object MessagingController {
  val THREAD_PAGE_SIZE = 20
}


class MessagingController(threadRepo: MessageThreadRepo, userThreadRepo: UserThreadRepo, messageRepo: MessageRepo, shoebox: ShoeboxServiceClient, db: Database) extends Logging {

  private var notificationCallbacks = Vector[(Option[Id[User]], Notification) => Unit]()


  private def sendNotificationForMessage(user: Id[User], message: Message, thread: MessageThread) : Unit = { //TODO Stephen: Construct and store notification json
    db.readWrite{ implicit session => 
      userThreadRepo.setNotification(user, thread.id.get)
    }
    notificationCallbacks.par.foreach{ f => 
      f(Some(user), Notification(thread.id.get, message.id.get, JsNull))
    }
  }


  def onNotification(f: (Option[Id[User]], Notification) => Unit) : Unit = {
    notificationCallbacks = notificationCallbacks :+ f
  }


  def sendNewMessage(from: Id[User], recipients: Set[Id[User]], urlOpt: Option[String], messageText: String) : Message = {
    val participants = recipients + from
    val uriIdOpt = urlOpt.map{url: String => Await.result(shoebox.normalizeURL(url), 10 seconds)}
    val thread = db.readWrite{ implicit session => 
      val thread = threadRepo.getOrCreate(participants, urlOpt, uriIdOpt) 
      participants.par.foreach{ userId => 
        userThreadRepo.createIfNotExists(userId, thread.id.get, uriIdOpt)
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


  def sendMessage(from: Id[User], thread: MessageThread, messageText: String, urlOpt: Option[String]) : Message = { //TODO Stephen: Update last_msg_from_other
    if (! thread.containsUser(from)) throw NotAuthorizedException()
    log.info(s"Sending message '$messageText' from $from to ${thread.participants}")
    val message = db.readWrite{ implicit session => 
      val message = messageRepo.create(from, thread, messageText, urlOpt)
      thread.allUsersExcept(from).foreach { userId =>
        userThreadRepo.setLastMsgFromOther(userId, thread.id.get, message.id.get)
      }
      message
    }

    thread.allUsersExcept(from).foreach { userId =>
      sendNotificationForMessage(userId, message, thread)
    }
    //async update normalized url id so as not to block on that (the shoebox call yields a future)
    urlOpt.foreach{ url =>
      shoebox.normalizeURL(url).foreach{ uriId =>
        db.readWrite { implicit session => 
          messageRepo.updateUriId(message, uriId)
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
 

  def setLastSeen(userId: Id[User], threadId: Id[MessageThread]) : Unit = {
    db.readWrite{ implicit session =>
      userThreadRepo.setLastSeen(userId, threadId)
    }
  }


  def getPendingNotifications(userId: Id[User]) : Seq[Notification] = {
    db.readOnly{ implicit session =>
      userThreadRepo.getPendingNotifications(userId)
    }
  } 


}

