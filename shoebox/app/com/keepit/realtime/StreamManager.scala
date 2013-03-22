package com.keepit.realtime

import java.util.concurrent.ConcurrentHashMap
import com.keepit.common.db.Id
import com.keepit.model._
import play.api.libs.iteratee.Enumerator
import play.api.libs.json._
import com.google.inject.{Inject, Singleton, ImplementedBy}
import com.keepit.common.logging.Logging
import com.keepit.common.db.slick.Database
import akka.actor.ActorSystem
import scala.collection.JavaConverters._

trait StreamManager[T, S] {
  def connect(identifier: T): Enumerator[S]
  def isConnected(identifier: T): Boolean
  def disconnect(identifier: T): Unit
  def disconnectAll(identifier: T): Unit
  def push(identifier: T, msg: S): Unit
  def broadcast(msg: S): Unit
}


trait MultiClientStreamManager[T, S] extends StreamManager[T, S] with Logging {

  protected val clients = (new ConcurrentHashMap[T, ClientStreamLike[S]]()).asScala

  def connect(clientIdentifier: T): Enumerator[S] = {
    log.info(s"Client $clientIdentifier connecting.")
    val clientStream = clients.getOrElse(clientIdentifier, {
      log.info(s"Client $clientIdentifier doesn't exist. Creating.")
      val stream = new ClientStream[T, S](clientIdentifier)
      clients.put(clientIdentifier, stream)
      stream
    })
    clientStream.connect
  }

  def isConnected(clientIdentifier: T): Boolean =
    clients.contains(clientIdentifier)

  def disconnect(clientIdentifier: T) {
   clients.get(clientIdentifier).map { client =>
      client.disconnect()
      if (!client.hasListeners) {
        clients.remove(clientIdentifier).foreach { client =>
          log.info(s"Client $client disconnected.")
          client.close()
        }
      }
    }
  }

  def disconnectAll(clientIdentifier: T): Unit = {
    clients.remove(clientIdentifier).foreach(_.close())
    log.info(s"Client $clientIdentifier disconnected.")
  }

  def push(clientIdentifier: T, msg: S): Unit = {
    clients.get(clientIdentifier).map { client =>
      client.push(msg)
    }
  }

  def broadcast(msg: S): Unit = {
    for (client <- clients.values) {
      client.push(msg)
    }
  }
}

trait UserStreamManager extends MultiClientStreamManager[Id[User], JsArray] {
  def push(clientIdentifier: Id[User], messageType: String, msg: JsValue): Unit = {
    push(clientIdentifier, Json.arr(messageType, msg))
  }

  def broadcast(messageType: String, msg: JsValue): Unit = {
    broadcast(Json.arr(messageType, msg))
  }
}

@Singleton
class DouglasAdamsQuoteStreamManager @Inject() (streams: Streams, system: ActorSystem) extends UserStreamManager {
  import scala.concurrent.ExecutionContext.Implicits.global
  import play.api.libs.concurrent.Akka
  import scala.concurrent.duration._
  import play.api.Play.current
  import play.api.libs.json.Json
  import com.keepit.common.admin.DouglasAdamsQuotes

  override def connect(userId: Id[User]) = {
    val maybeCancel = if (!isConnected(userId)) {
      Some(system.scheduler.schedule(5.seconds, 10.seconds) {
        log.info("Sending quote!")
        push(userId, Json.arr("quote", DouglasAdamsQuotes.random.quote))
      })
    } else None
    val enumerator = super.connect(userId)

    for (cancel <- maybeCancel; client <- clients.get(userId)) {
      client.attach(cancel)
    }

    enumerator
  }
}


@Singleton
class UserDefaultStreamManager @Inject() (streams: Streams) extends UserStreamManager {
  override def connect(userId: Id[User]) = {
    super.connect(userId) >- streams.welcome(userId)
  }
}

@Singleton
class UserNotificationStreamManager @Inject() (streams: Streams)  extends UserStreamManager {
  override def connect(userId: Id[User]) = {
    super.connect(userId) >- streams.unreadNotifications(userId)
  }
}

@Singleton
class AdminEventStreamManager extends UserStreamManager

@Singleton
class UserStreamProvider @Inject() (userDefault: UserDefaultStreamManager, userNotification: UserNotificationStreamManager, adminEvent: AdminEventStreamManager, hgttg: DouglasAdamsQuoteStreamManager){
  def getStreams(feeds: Seq[String]): Seq[UserStreamManager] = {
    feeds.collect {
        case "notifications" =>  userNotification
        case "hgttg" => hgttg
        case "admin" => adminEvent
    } :+ userDefault
  }
}



