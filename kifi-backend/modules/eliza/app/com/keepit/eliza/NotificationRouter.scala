package com.keepit.eliza

import com.keepit.model.{User}
import com.keepit.common.db.{Id}
import com.keepit.common.time._
import com.keepit.common.logging.Logging

import play.api.libs.json.{JsArray, Json}
import play.modules.statsd.api.Statsd

import scala.collection.concurrent.TrieMap

import org.joda.time.DateTime

import com.google.inject.{Inject, Singleton, ImplementedBy}

@ImplementedBy(classOf[NotificationRouterImpl])
trait NotificationRouter { //TODO Stephen: This needs a better name
  
  def sendNotification(userOpt: Option[Id[User]], notification: Notification) : Unit

  def onNotification(f: (Option[Id[User]], Notification) => Unit) : Unit

  def registerUserSocket(socket: SocketInfo): Unit

  def unregisterUserSocket(socket: SocketInfo) : Unit

  def sendToUser(userId: Id[User], data: JsArray) : Unit

  def sendToUserNoBroadcast(userId: Id[User], data: JsArray) : Unit

  def sendToAllUsers(data: JsArray) : Unit

  def connectedSockets : Int
}

@Singleton
class NotificationRouterImpl @Inject() (elizaServiceClient: ElizaServiceClient) extends NotificationRouter with Logging { 

  private var notificationCallbacks = Vector[(Option[Id[User]], Notification) => Unit]()
  private val userSockets = TrieMap[Id[User], TrieMap[Long, SocketInfo]]() 


  private def logTiming(tag: String, msg: JsArray) = {
    try {
      if(msg(0).as[String] == "message") {
        val createdAt = Json.fromJson[DateTime](msg(2) \ "createdAt")(DateTimeJsonFormat).get
        val now = currentDateTime
        val diff = now.getMillis - createdAt.getMillis
        Statsd.timing(s"websocket.delivery.$tag.message", now.getMillis - createdAt.getMillis)
      } else if(msg(0).as[String] == "notification") {
        val createdAt = Json.fromJson[DateTime](msg(1) \ "time").get
        val now = currentDateTime
        val diff = now.getMillis - createdAt.getMillis
        Statsd.timing(s"websocket.delivery.$tag.notice", now.getMillis - createdAt.getMillis)
      }
    } catch {
      case ex: Throwable => log.warn(s"Error with statsd tacking: $ex")
    }
  }

  def registerUserSocket(socket: SocketInfo): Unit = {
    val sockets = userSockets.getOrElseUpdate(socket.userId, TrieMap[Long, SocketInfo]())
    sockets(socket.id) = socket
  }

  def unregisterUserSocket(socket: SocketInfo) : Unit = {
    val sockets = userSockets.getOrElseUpdate(socket.userId, TrieMap[Long, SocketInfo]())
    sockets.remove(socket.id)
  }

  def sendNotification(userOpt: Option[Id[User]], notification: Notification) : Unit = {
    notificationCallbacks.par.foreach { f => 
      f(userOpt, notification)
    }
  }

  def onNotification(f: (Option[Id[User]], Notification) => Unit) : Unit = {
    notificationCallbacks = notificationCallbacks :+ f
  }

  private def sendToUserNoBroadcastNoLog(userId: Id[User], data: JsArray) : Unit = {
    val sockets = userSockets.get(userId).map(_.values.toSeq).getOrElse(Seq())
    sockets.foreach{ socket =>
      socket.channel.push(data)
    }
  }

  def sendToUser(userId: Id[User], data: JsArray) : Unit = {
    sendToUserNoBroadcastNoLog(userId, data)
    logTiming("local", data)
    elizaServiceClient.sendToUserNoBroadcast(userId, data)
  }

  def sendToUserNoBroadcast(userId: Id[User], data: JsArray) : Unit = {
    sendToUserNoBroadcastNoLog(userId, data)
    logTiming("remote", data)
  }

  def sendToAllUsers(data: JsArray) : Unit = {
    userSockets.values.foreach{ socketMap =>
      socketMap.values.foreach{ socket =>
        socket.channel.push(data)
      }
    }
  }


  def connectedSockets : Int = userSockets.values.map{_.keys.toSeq.length}.sum

}
