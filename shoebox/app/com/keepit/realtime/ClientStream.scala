package com.keepit.realtime

import java.util.concurrent.atomic.AtomicInteger
import scala.collection.concurrent.{Map => ConcurrentMap}
import com.keepit.common.akka.FortyTwoActor
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import akka.pattern.ask
import play.api._
import play.api.Play.current
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.iteratee._
import akka.actor.Cancellable

trait ClientStreamLike[S] {
  def connect(): Enumerator[S]
  def disconnect(): Unit
  def attach(cancellable: Cancellable)
  def push(msg: S): Unit
  def close(): Unit
  def hasListeners: Boolean
  def getConnectionCount: Int
}



class ClientStream[T, S](val identifier: T) extends ClientStreamLike[S] with Logging {
  private var connections = new AtomicInteger(0)
  private var cancellables: Seq[Cancellable] = Seq()

  val (enumerator, channel) = Concurrent.broadcast[S]

  def connect(): Enumerator[S] = {
    log.info(s"connect() for client ${identifier}")
    connections.incrementAndGet()
    enumerator
  }

  def disconnect(): Unit = {
    log.info(s"disconnect() for client ${identifier}")
    connections.decrementAndGet()
  }

  def attach(cancellable: Cancellable) = {
    cancellables :+= cancellable
  }

  def push(payload: S): Unit = channel.push(payload)

  def close() {
    log.info(s"close() for client ${identifier}")
    connections.set(0)
    cancellables.map(_.cancel)
    channel.eofAndEnd()
  }
  def hasListeners: Boolean = getConnectionCount != 0

  def getConnectionCount: Int = connections.get

}

