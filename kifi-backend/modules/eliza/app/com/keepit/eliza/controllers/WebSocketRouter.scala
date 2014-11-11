package com.keepit.eliza.controllers

import com.keepit.eliza.ElizaServiceClient
import com.keepit.model.User
import com.keepit.common.db.Id
import com.keepit.common.time._
import com.keepit.common.logging.Logging
import com.keepit.eliza.model._

import play.api.libs.json.{ JsArray, Json }

import scala.collection.concurrent.TrieMap
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.concurrent.duration._

import akka.actor.ActorSystem

import org.joda.time.DateTime

import com.google.inject.{ Inject, Singleton, ImplementedBy }

@ImplementedBy(classOf[WebSocketRouterImpl])
trait WebSocketRouter {

  def sendNotification(userOpt: Option[Id[User]], notification: Notification): Unit

  def onNotification(f: (Option[Id[User]], Notification) => Unit): Unit

  def registerUserSocket(socket: SocketInfo): Unit

  def unregisterUserSocket(socket: SocketInfo): Unit

  def getArbitrarySocketInfo: Option[SocketInfo]

  def sendToUser(userId: Id[User], data: JsArray): Unit

  def sendToUserNoBroadcast(userId: Id[User], data: JsArray): Unit

  def sendToAllUsers(data: JsArray): Unit

  def connectedSockets: Int

  def socketLastTracked(userId: Id[User]): Option[DateTime]

  def setSocketLastTracked(userId: Id[User]): Unit
}

@Singleton
class WebSocketRouterImpl @Inject() (
    elizaServiceClient: ElizaServiceClient,
    system: ActorSystem) extends WebSocketRouter with Logging {

  system.scheduler.schedule(30 seconds, 1 minutes)(updateStatsD _)

  private var notificationCallbacks = Vector[(Option[Id[User]], Notification) => Unit]()
  private val userSockets = TrieMap[Id[User], TrieMap[Long, SocketInfo]]()
  private val userSocketLastTracked = TrieMap[Id[User], DateTime]()

  def getArbitrarySocketInfo: Option[SocketInfo] = userSockets.values.map(_.values).flatten.headOption

  private def logTiming(tag: String, msg: JsArray) = {
    try {
      if (msg(0).as[String] == "message") {
        val createdAt = Json.fromJson[DateTime](msg(2) \ "createdAt")(DateTimeJsonFormat).get
        val now = currentDateTime
        val diff = now.getMillis - createdAt.getMillis
        statsd.timing(s"websocket.delivery.$tag.message", diff, ONE_IN_TEN_THOUSAND)
      } else if (msg(0).as[String] == "notification") {
        val createdAt = Json.fromJson[DateTime](msg(1) \ "time").get
        val now = currentDateTime
        val diff = now.getMillis - createdAt.getMillis
        statsd.timing(s"websocket.delivery.$tag.notice", diff, ONE_IN_TEN_THOUSAND)
      }
    } catch {
      case ex: Throwable => log.warn(s"Error with statsd tacking: $ex")
    }
  }

  def registerUserSocket(socket: SocketInfo): Unit = {
    val sockets = userSockets.getOrElseUpdate(socket.userId, TrieMap[Long, SocketInfo]())
    sockets(socket.id) = socket
  }

  def unregisterUserSocket(socket: SocketInfo): Unit = {
    val sockets = userSockets.getOrElseUpdate(socket.userId, TrieMap[Long, SocketInfo]())
    sockets.remove(socket.id)
  }

  def sendNotification(userOpt: Option[Id[User]], notification: Notification): Unit = {
    notificationCallbacks.par.foreach { f =>
      f(userOpt, notification)
    }
  }

  def onNotification(f: (Option[Id[User]], Notification) => Unit): Unit = {
    notificationCallbacks = notificationCallbacks :+ f
  }

  private def sendToUserNoBroadcastNoLog(userId: Id[User], data: JsArray): Unit = {
    val sockets = userSockets.get(userId).map(_.values.toSeq).getOrElse(Seq())
    sockets.par.foreach { socket =>
      socket.channel.push(data)
    }
  }

  def sendToUser(userId: Id[User], data: JsArray): Unit = {
    sendToUserNoBroadcastNoLog(userId, data)
    logTiming("local", data)
    elizaServiceClient.sendToUserNoBroadcast(userId, data)
  }

  def sendToUserNoBroadcast(userId: Id[User], data: JsArray): Unit = {
    sendToUserNoBroadcastNoLog(userId, data)
    logTiming("remote", data)
  }

  def sendToAllUsers(data: JsArray): Unit = {
    userSockets.values.foreach { socketMap =>
      socketMap.values.foreach { socket =>
        socket.channel.push(data)
      }
    }
  }

  private def updateStatsD(): Unit = {
    elizaServiceClient.connectedClientCount.map { countSeq =>
      val count: Int = countSeq.sum + connectedSockets
      statsd.gauge("websocket.channel.user.client", count)
    }
  }

  def connectedSockets: Int = userSockets.values.map { _.keys.toSeq.length }.sum

  def socketLastTracked(userId: Id[User]): Option[DateTime] = {
    userSocketLastTracked.get(userId)
  }

  def setSocketLastTracked(userId: Id[User]): Unit = {
    userSocketLastTracked += (userId -> currentDateTime)
  }
}
