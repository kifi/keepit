package com.keepit.realtime

import java.util.concurrent.ConcurrentHashMap
import com.keepit.common.db.Id
import com.keepit.model.User
import play.api.libs.iteratee.Enumerator
import play.api.libs.json.{JsObject, JsValue}
import com.google.inject.{Inject, Singleton, ImplementedBy}
import com.keepit.common.logging.Logging

@Singleton
class StreamManager extends Logging {
  type Message = JsObject

  private var clients = new ConcurrentHashMap[Id[User], ClientStreamLike[JsValue]]()

  def connect(userId: Id[User]): Enumerator[JsValue] = {
    log.info(s"Client $userId connecting.")
    val clientStream = Option(clients.get(userId)).getOrElse {
      log.info(s"Client $userId doesn't exist. Creating.")
      val stream = new ClientStream[JsValue](userId)
      clients.put(userId, stream)
      stream
    }
    clientStream.connect
  }

  def disconnect(userId: Id[User]) {
    Option(clients.get(userId)).map { client =>
      client.disconnect()
      if(!client.hasListeners) {
        client.close()
        clients.remove(userId)
        log.info(s"Client $userId disconnected.")
      }
    }
  }

  def disconnectAll(userId: Id[User]) {
    Option(clients.get(userId)).map { client =>
      client.close()
      clients.remove(userId)
      log.info(s"Client $userId disconnected.")
    }
  }

  def push(userId: Id[User], msg: JsObject) {
    Option(clients.get(userId)).map { client =>
      client.push(msg)
    }
  }

  def pushAll(msg: JsObject) {
    import scala.collection.JavaConversions.mapAsScalaConcurrentMap
    for((_, client) <- clients) {
      client.push(msg)
    }
  }
}