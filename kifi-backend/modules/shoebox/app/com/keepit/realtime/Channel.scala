package com.keepit.realtime

import scala.collection.concurrent.TrieMap
import com.google.inject.{ImplementedBy, Inject, Singleton}
import com.keepit.common.db.Id
import com.keepit.model.User
import play.api.libs.iteratee.Concurrent.{Channel => PlayChannel}
import play.api.libs.json.JsArray
import com.keepit.model.NormalizedURI
import java.util.concurrent.atomic.AtomicBoolean
import com.keepit.common.logging.Logging
import play.api.Plugin
import com.keepit.common.plugin.{SchedulingProperties, SchedulingPlugin}
import akka.actor.ActorSystem
import akka.util.Timeout
import scala.concurrent.duration._
import play.modules.statsd.api.Statsd
import scala.concurrent.{Promise, Future}
import com.keepit.shoebox.ShoeboxServiceClient
import scala.concurrent.ExecutionContext.Implicits.global

/** A Channel, which accepts pushed messages, and manages connections to it.
  */
trait Channel {
  /** Subscribe to this Channel.
   *
   *  This is called by a ChannelManager when a client wants to subscribe to this channel's messages.
   *  The caller must provide a unique identifier, socketId, for the client, and a play.api.libs.iteratee.Concurrent.Channel which will receive messages.
   */
  def subscribe(socketId: Long, channel: PlayChannel[JsArray]): Boolean

  /** Unsubscribe from this Channel.
   *
   *  This is called by a Channel Manager when a client wants to unsubscribe to this channel's messages.
   *  The caller provides the same unique identifier (socketId) as provided to `subscribe()`
   */
  def unsubscribe(socketId: Long): Boolean

  /** Unsubscribe all clients from this Channel.
   *
   *  All connected clients will be unsubscribed.
   */
  def unsubscribeAll(): Unit

  /** Map a function upon all connected connected channels
   */
  def map[T](f: ((Long, PlayChannel[JsArray])) => T): Seq[T]

  /** Push a message to this Channel.
   *
   *  The message will be broadcasted to all connected clients, using their provided iteratee.Concurrent.Channel.
   */
  def push(msg: JsArray): Int

  /** Returns the number of clients currently connected.
   */
  def size: Int

  /** Returns whether no clients are currently connected.
   */
  def isEmpty: Boolean
}

trait ChannelManager[T, S <: Channel] {
  /** Subscribe to a channel managed by this ChannelManager.
   *
   *  This is called by a client when it wants to subscribe to this channel's messages.
   *  The caller must provide a unique identifier, socketId, for the client, and a play.api.libs.iteratee.Concurrent.Channel which will receive messages.
   *  This returns a `Subscription`
   */
  def subscribe(channelId: T, socketId: Long, channel: PlayChannel[JsArray]): Subscription

  /** Unsubscribe from a channel managed by this ChannelManager.
   *
   *  This is called by a client when it wants to unsubscribe to this channel's messages.
   *  The caller provides the same unique identifier (socketId) as provided to `subscribe()`
   */
  def unsubscribe(channelId: T, socketId: Long): Option[Boolean]

  /** Push a message to the channels managed by this ChannelManager, and fanout the message to other clients.
   *
   *  The message will be sent to the specified channel locally and to `fanout(id, msg)`, and will
   *  return a future to the number of channels the message was sent to.
   */
  def pushAndFanout(channelId: T, msg: JsArray): Future[Int]

  /** Push a message to the local channels managed by this ChannelManager.
    *
    *  The message will be sent to the specified channel locally and will
    *  return the number of channels the message was sent to.
    */
  def pushNoFanout(id: T, msg: JsArray): Int

  /** Broadcast a message to all local channels managed by this ChannelManager.
   *
   *  The message will be sent to all local channels, and will return the number of channels the message was sent to.
   */
  def broadcastNoFanout(msg: JsArray): Int

  /** Broadcast a message to all channels managed by this ChannelManager, and fanout the message to other clients.
    *
    *  The message will be sent to all local and remote channels, and will return a
    *  future to the number of channels the message was sent to.
    */
  def broadcast(msg: JsArray): Future[Int]

  /** Returns the number of currently connected clients.
   */
  def localClientCount: Int

  def globalClientCount: Future[Int]

  /** Returns whether a client is connected or not to a given channel.
   */
  def isConnected(channelId: T): Boolean
}

class Subscription(val name: String, unsub: () => Option[Boolean]) {
  private val active = new AtomicBoolean(true)
  def isActive: Boolean = active.get()
  def unsubscribe(): Option[Boolean] = {
    if (active.getAndSet(false)) unsub() else None
  }
}

abstract class ChannelImpl[T](id: T) extends Channel {
  // The concurrent TrieMap is particularly good for O(1), atomic, lock-free snapshots for size, iterator, and clear
  // http://lampwww.epfl.ch/~prokopec/ctries-snapshot.pdf
  private val pool = TrieMap[Long, PlayChannel[JsArray]]()

  def subscribe(socketId: Long, channel: PlayChannel[JsArray]): Boolean = {
    pool.put(socketId, channel).isDefined
  }

  def unsubscribe(socketId: Long): Boolean = {
    pool.remove(socketId).isDefined
  }

  def unsubscribeAll(): Unit = {
    pool.clear
  }

  def map[T](f: ((Long, PlayChannel[JsArray])) => T): Seq[T] = {
    pool.view.map(f).toSeq
  }

  def push(msg: JsArray): Int = {
    pool.map(s => s._2.push(msg)).size
  }

  def size: Int = pool.size
  def isEmpty: Boolean = pool.isEmpty
}

abstract class ChannelManagerImpl[T, S <: Channel](name: String, creator: T => S) extends ChannelManager[T, S] with Logging {
  protected[this] val channels = TrieMap[T, S]()

  @scala.annotation.tailrec
  final def subscribe(id: T, socketId: Long, playChannel: PlayChannel[JsArray]): Subscription = {
    val channel = findOrCreateChannel(id)
    channel.subscribe(socketId, playChannel)
    find(id) match {
      case Some(ch) if ch eq channel =>
        new Subscription(s"$name:$id", () => unsubscribe(id, socketId))
      case _ =>
        // channel was removed before we subscribed, so try again
        channel.unsubscribe(socketId)
        subscribe(id, socketId, playChannel)
    }
  }

  final def unsubscribe(id: T, socketId: Long): Option[Boolean] = {
    find(id).map { channel =>
      val res = channel.unsubscribe(socketId)
      if (channel.isEmpty) {
        channels.remove(id)
      }
      res
    }
  }

  def pushNoFanout(id: T, msg: JsArray): Int = {
    find(id).map(_.push(msg)).getOrElse(0)
  }

  def pushAndFanout(id: T, msg: JsArray): Future[Int] = {
    val localTotal = pushNoFanout(id, msg)
    fanout(id, msg).map(t => t + localTotal)
  }

  def fanout(id: T, msg: JsArray): Future[Int]

  def broadcastNoFanout(msg: JsArray): Int = {
    channels.map(_._2.push(msg)).sum
  }

  def broadcastFanout(msg: JsArray): Future[Int]

  def broadcast(msg: JsArray): Future[Int] = {
    val localTotal = broadcastNoFanout(msg)
    broadcastFanout(msg).map(t => t + localTotal)
  }

  def clientCountFanout(): Future[Int]

  def globalClientCount: Future[Int] = {
    clientCountFanout.map(_ + localClientCount)
  }

  def localClientCount: Int = {
    channels.map(_._2.size).sum
  }

  def isConnected(id: T): Boolean = {
    channels.get(id).isDefined
  }

  private def findOrCreateChannel(id: T): Channel = {
    channels.getOrElseUpdate(id, creator(id))
  }

  private def find(id: T): Option[Channel] = {
    channels.get(id)
  }

}

// Used for user-specific transmissions, such as notifications.
class UserSpecificChannel(id: Id[User]) extends ChannelImpl(id)
@Singleton class UserChannel @Inject() (shoeboxServiceClient: ShoeboxServiceClient) extends ChannelManagerImpl("user", (id: Id[User]) => new UserSpecificChannel(id)) {

  def fanout(id: Id[User], msg: JsArray): Future[Int] = {
    Future.sequence(shoeboxServiceClient.userChannelFanout(id, msg)).map(_.sum)
  }

  def broadcastFanout(msg: JsArray): Future[Int] = {
    Future.sequence(shoeboxServiceClient.userChannelBroadcastFanout(msg)).map(_.sum)
  }

  def clientCountFanout(): Future[Int] = {
    Future.sequence(shoeboxServiceClient.userChannelCountFanout()).map(_.sum)
  }

  def closeAllChannels() = {
    channels.map({ case (id, chan) =>
      chan.map({ case (_, playChannel) => playChannel.eofAndEnd() })
    })
    channels.clear()
  }
}

// Used for page-specific transmissions, such as new comments.
class UriSpecificChannel(uri: String) extends ChannelImpl(uri)
@Singleton class UriChannel @Inject() (shoeboxServiceClient: ShoeboxServiceClient) extends ChannelManagerImpl("uri", (uri: String) => new UriSpecificChannel(uri)) {

  def broadcastFanout(msg: JsArray): Future[Int] = {
    Promise.successful(0).future
  }

  def clientCountFanout(): Future[Int] = {
    Future.sequence(shoeboxServiceClient.uriChannelCountFanout()).map(_.sum)
  }

  def fanout(id: String, msg: JsArray): Future[Int] = {
    Future.sequence(shoeboxServiceClient.uriChannelFanout(id, msg)).map(_.sum)
  }
}

trait ChannelPlugin extends Plugin {
  def reportUserClientCount(): Future[Int]
  def reportURIClientCount(): Future[Int]
}

@Singleton
class ChannelPluginImpl @Inject() (
  system: ActorSystem,
  userChannel: UserChannel,
  uriChannel: UriChannel,
  val schedulingProperties: SchedulingProperties) //only on leader
  extends ChannelPlugin with SchedulingPlugin with Logging {

  // plugin lifecycle methods
  override def enabled: Boolean = true
  override def onStart() {
    log.info("starting ChannelPluginImpl")
    scheduleTask(system, 0 seconds, 1 minute, "report user client count") {reportUserClientCount()}
    scheduleTask(system, 0 seconds, 1 minute, "report uri client count") {reportURIClientCount()}
  }
  override def onStop() {
    userChannel.closeAllChannels()
    log.info("stopping ChannelPluginImpl")
  }

  def reportUserClientCount() = {
    userChannel.globalClientCount.map { count =>
      log.info(s"[userChannel] $count active connections")
      Statsd.gauge("websocket.channel.user.client", count)
      count
    }
  }

  def reportURIClientCount() = {
    uriChannel.globalClientCount.map { count =>
      val count = uriChannel.localClientCount
      Statsd.gauge("websocket.channel.uri.client", count)
      count
    }
  }
}

