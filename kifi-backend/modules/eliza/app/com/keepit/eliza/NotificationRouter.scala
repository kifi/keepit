package com.keepit.eliza

import com.keepit.model.{User}
import com.keepit.common.db.{Id}

import play.api.libs.json.JsArray

import scala.collection.concurrent.TrieMap

import com.google.inject.{Inject, Singleton, ImplementedBy}

@ImplementedBy(classOf[NotificationRouterImpl])
trait NotificationRouter { //TODO Stephen: This needs a better name
  
  def sendNotification(userOpt: Option[Id[User]], notification: Notification) : Unit

  def onNotification(f: (Option[Id[User]], Notification) => Unit) : Unit

  def registerUserSocket(socket: SocketInfo): Unit

  def unregisterUserSocket(socket: SocketInfo) : Unit

  def sendToUser(userId: Id[User], data: JsArray) : Unit

  def sendToUserNoBroadcast(userId: Id[User], data: JsArray) : Unit

  def connectedSockets : Int
}

@Singleton
class NotificationRouterImpl @Inject() (elizaServiceClient: ElizaServiceClient) extends NotificationRouter { 

  private var notificationCallbacks = Vector[(Option[Id[User]], Notification) => Unit]()
  private val userSockets = TrieMap[Id[User], TrieMap[Long, SocketInfo]]() 


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

  def sendToUser(userId: Id[User], data: JsArray) : Unit = {
    sendToUserNoBroadcast(userId, data)
    elizaServiceClient.sendToUserNoBroadcast(userId, data)
  }

  def sendToUserNoBroadcast(userId: Id[User], data: JsArray) : Unit = {
    val sockets = userSockets.get(userId).map(_.values.toSeq).getOrElse(Seq())
    sockets.foreach{ socket =>
      socket.channel.push(data)
    }
  }

  def connectedSockets : Int = userSockets.values.map{_.keys.toSeq.length}.sum

}
