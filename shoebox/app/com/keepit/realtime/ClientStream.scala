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
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.stm._

/** A ClientStream, which provides a convenient wrapper around .
  *
  * @constructor create a new ClientStream with an identifier
  * @param identifier the identifier for this ClientStream (typically a user Id)
  */
trait ClientStreamLike[S] {
  /** Connect to this ClientStream.
   *
   *  This should be called both when a new client connects, and a current client connects from a different source.
   */
  def connect(): Enumerator[S]

  /** Disconnect from this ClientStream.
   *
   *  This tells the ClientStream that a client has disconnected from one of its connections.
   *  Calling disconnect() does not mean that the ClientStream is closed, if other connections for this client existed already.
   */
  def disconnect(): Unit

  /** Pass in a Cancellable, which will be called with .cancel() automatically when this ClientStream is finished.
   *
   *  This lets Futures or Scheduled tasks to be stopped when the ClientStream is finished.
   */
  def attach(cancellable: Cancellable)

  /** Push a message through this ClientStream, to be broadcasted to all connected clients
   */
  def push(msg: S): Unit

  /** End this ClientStream.
   *
   *  This either disconnects all connected clients, or is called when the last client disconnected already.
   */
  def close(): Unit

  /** Returns if this ClientStream has any connected clients.
   */
  def hasListeners: Boolean

  /** Returns the current number of concurrent connected clients.
   */
  def getConnectionCount: Int
}



class ClientStream[T, S](val identifier: T) extends ClientStreamLike[S] with Logging {
  private val connections = new AtomicInteger(0)
  private val cancellables: Ref[Seq[Cancellable]] = Ref(Nil)

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
    atomic { implicit t =>
      cancellables.set(cancellables.get :+ cancellable)
    }
  }

  def push(payload: S): Unit = channel.push(payload)

  def close() {
    atomic { implicit t =>
      log.info(s"close() for client ${identifier}")
      connections.set(0)
      cancellables.get.map(_.cancel)
      channel.eofAndEnd()
    }
  }
  def hasListeners: Boolean = getConnectionCount != 0

  def getConnectionCount: Int = connections.get

}

