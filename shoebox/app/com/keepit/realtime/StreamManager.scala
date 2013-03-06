package com.keepit.realtime

import java.util.concurrent.ConcurrentHashMap
import com.keepit.common.db.Id
import com.keepit.model._
import play.api.libs.iteratee.Enumerator
import play.api.libs.json.{JsObject, JsValue}
import com.google.inject.{Inject, Singleton, ImplementedBy}
import com.keepit.common.logging.Logging
import com.keepit.common.db.slick.Database
import akka.actor.ActorSystem

trait StreamManager[T, S] {
  def connect(identifier: T): Enumerator[T]
  def clientIsConnected: Boolean
  def disconnect(identifier: T): Unit
  def disconnectAll(): Unit
  def push(idenifier: T, msg: S): Unit
  def pushAll(msg: S): Unit
}


trait MultiClientStreamManager[T, S] extends StreamManager[T, S] with Logging {

  private var clients = new ConcurrentHashMap[T, ClientStreamLike[S]]()
  protected def safeGet(key: T) = Option(clients.get(key))

  def connect(clientIdentifier: T): Enumerator[S] = {
    log.info(s"Client $clientIdentifier connecting.")
    val clientStream = safeGet(clientIdentifier).getOrElse {
      log.info(s"Client $clientIdentifier doesn't exist. Creating.")
      val stream = new ClientStream[T, S](clientIdentifier)
      clients.put(clientIdentifier, stream)
      stream
    }
    clientStream.connect
  }

  def clientIsConnected(clientIdentifier: T): Boolean =
    safeGet(clientIdentifier).isDefined

  def disconnect(clientIdentifier: T) {
   safeGet(clientIdentifier).map { client =>
      client.disconnect()
      if(!client.hasListeners) {
        client.close()
        clients.remove(clientIdentifier)
        log.info(s"Client $clientIdentifier disconnected.")
      }
    }
  }

  def disconnectAll(clientIdentifier: T) {
    safeGet(clientIdentifier).map { client =>
      client.close()
      clients.remove(clientIdentifier)
      log.info(s"Client $clientIdentifier disconnected.")
    }
  }

  def push(clientIdentifier: T, msg: S) {
    safeGet(clientIdentifier).map { client =>
      client.push(msg)
    }
  }

  def pushAll(msg: S) {
    import scala.collection.JavaConversions.mapAsScalaConcurrentMap
    for((_, client) <- clients) {
      client.push(msg)
    }
  }
}

trait UserStreamManager extends MultiClientStreamManager[Id[User], JsValue]

@Singleton
class DouglasAdamsQuoteStreamManager @Inject() (streams: Streams, system: ActorSystem) extends UserStreamManager {
  import scala.concurrent.ExecutionContext.Implicits.global
  import play.api.libs.concurrent.Akka
  import scala.concurrent.duration._
  import play.api.Play.current
  import play.api.libs.json.Json
  import com.keepit.common.admin.DouglasAdamsQuotes

  override def connect(userId: Id[User]) = {
    val maybeCancel = if(!clientIsConnected(userId)) {
      Some(system.scheduler.schedule(5.seconds, 10.seconds) {
        log.info("Sending quote!")
        push(userId, Json.obj("quote" -> DouglasAdamsQuotes.random.quote))
      })
    } else None
    val enumerator = super.connect(userId)

    if(maybeCancel.isDefined) {
      safeGet(userId).map(_.attach(maybeCancel.get))
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
class UserStreamProvider @Inject() (userDefault: UserDefaultStreamManager, userNotification: UserNotificationStreamManager, hgttg: DouglasAdamsQuoteStreamManager){
  def getStreams(feeds: Seq[String]): Seq[UserStreamManager] = {
    feeds.collect {
        case "notifications" =>  userNotification
        case "hgttg" => hgttg
    } :+ userDefault
  }
}



