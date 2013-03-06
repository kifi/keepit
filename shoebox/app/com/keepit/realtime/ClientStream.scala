package com.keepit.realtime

import akka.actor._
import scala.concurrent.duration._
import play.api._
import play.api.libs.json._
import play.api.libs.iteratee._
import play.api.libs.concurrent._
import akka.util.Timeout
import akka.pattern.ask
import play.api.Play.current
import play.api.libs.concurrent.Execution.Implicits._
import com.keepit.common.akka.FortyTwoActor
import scala.concurrent.Future
import com.keepit.serializer.EventSerializer
import com.google.inject.{Inject, Singleton, ImplementedBy}
import com.keepit.model._
import com.keepit.common.time._
import org.joda.time.DateTime
import com.keepit.inject._
import com.keepit.common.db.slick.Database
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap
import scala.collection.concurrent.{Map => ConcurrentMap}
import com.keepit.common.db._
import com.keepit.common.logging.Logging

trait ClientStreamLike[T] {
  val userId: Id[User]
  def connect(): Enumerator[T]
  def disconnect(): Unit
  def push(msg: T): Unit
  def close(): Unit
  def hasListeners: Boolean
  def getConnectionCount: Int
}



class ClientStream[T](val userId: Id[User]) extends ClientStreamLike[T] with Logging {
  private var connections = new AtomicInteger(0)

  val (enumerator, channel) = Concurrent.broadcast[T]

  def connect(): Enumerator[T] = {
    log.info(s"connect() for userId ${userId.id}")
    connections.incrementAndGet()
    enumerator
  }

  def disconnect(): Unit = {
    log.info(s"disconnect() for userId ${userId.id}")
    connections.decrementAndGet()
  }

  def push(json: T): Unit = channel.push(json)
  def close() {
    log.info(s"close() for userId ${userId.id}")
    connections.set(0)
    channel.eofAndEnd()
  }
  def hasListeners: Boolean = getConnectionCount != 0

  def getConnectionCount: Int = connections.get

}

