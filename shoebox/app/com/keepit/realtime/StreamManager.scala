package com.keepit.realtime

import java.util.concurrent.ConcurrentHashMap

import com.keepit.common.db.Id
import com.keepit.model.User

import play.api.libs.iteratee.Enumerator
import play.api.libs.json.JsObject
import com.google.inject.{Inject, Singleton, ImplementedBy}

@Singleton
class StreamManager {
  type Message = JsObject

  private var clients = new collection.mutable.HashMap[Id[User], ClientStreamLike[JsObject]]()

  def connect(userId: Id[User]): Enumerator[JsObject] = {
    println(s"Client $userId connecting.")
    val clientStream = Option(clients.get(userId)).getOrElse {
      println(s"Client $userId doesn't exist. Creating.")
      ClientStream[JsObject](userId)
    }
    clientStream.connect
  }

  def disconnect(userId: Id[User]) {
    Option(clients.get(userId)).map { client =>
      client.disconnect()
      if(!client.hasListeners) {
        client.close()
        clients.remove(userId)
      }
    }
  }

  def push(userId: Id[User], msg: JsObject) {
    println(s"Pushing for $userId")
    Option(clients.get(userId)).map{ client =>
      println("PUSHED!")
      client.push(msg)
    }
  }
}