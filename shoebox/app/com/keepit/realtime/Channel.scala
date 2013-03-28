package com.keepit.realtime

import scala.collection.concurrent.TrieMap
import com.google.inject.Singleton
import com.keepit.common.db.Id
import com.keepit.model.User
import play.api.libs.iteratee.Concurrent.{Channel => PlayChannel}
import play.api.libs.json.JsArray
import com.keepit.model.NormalizedURI
import java.util.concurrent.atomic.AtomicBoolean

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

  /** Push a message to this Channel.
   *
   *  The message will be broadcasted to all connected clients, using their provided iteratee.Concurrent.Channel.
   */
  def push(msg: JsArray): Int

  /** Returns the number of clients currently connected.
   */
  def size: Int
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

  /** Push a message to a channel managed by this ChannelManager.
   *
   *  The message will be sent to the specified channel, and will return the number of channels the message was sent to.
   */
  def push(channelId: T, msg: JsArray): Int
}

class Subscription(val name: String, unsub: () => Option[Boolean]) {
  private val active = new AtomicBoolean(true)
  def isActive = active.get()
  def unsubscribe(): Option[Boolean] = {
    val res = if(active.getAndSet(false)) unsub() else None
    res
  }
}

abstract class ChannelImpl[T](id: T) extends Channel {
  // The concurrent TrieMap is particularly good for O(1), atomic, lock-free snapshots for size, iterator, and clear
  // http://lampwww.epfl.ch/~prokopec/ctries-snapshot.pdf
  private val pool = TrieMap[Long, PlayChannel[JsArray]]()

  def size = pool.size

  def subscribe(socketId: Long, channel: PlayChannel[JsArray]): Boolean = {
    pool.put(socketId, channel).isDefined
  }

  def unsubscribe(socketId: Long): Boolean = {
    pool.remove(socketId).isDefined
  }

  def unsubscribeAll(): Unit = {
    pool.clear
  }

  def push(msg: JsArray): Int = {
    pool.map(s => s._2.push(msg)).size
  }
}

abstract class ChannelManagerImpl[T](name: String, creator: T => Channel) extends ChannelManager[T, Channel] {
  private val channels = TrieMap[T, Channel]()

  def subscribe(id: T, socketId: Long, channel: PlayChannel[JsArray]): Subscription = {
    findOrCreateChannel(id).subscribe(socketId, channel)

    new Subscription(s"$name:$id", () => unsubscribe(id, socketId))
  }

  def unsubscribe(id: T, socketId: Long): Option[Boolean] = {
    find(id).map { channel =>
      val res = channel.unsubscribe(socketId)
      // I'm not completely happy with this. It's not thread-safe if this runs at the same time as lines 77-78. Input? - Andrew
      if(channel.size == 0)
        channels.remove(id)
      res
    }
  }

  def push(id: T, msg: JsArray): Int = {
    find(id).map(_.push(msg)).getOrElse(0)
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
@Singleton class UserChannel extends ChannelManagerImpl("user", (id: Id[User]) => new UserSpecificChannel(id))

// Used for page-specific transmissions, such as new comments.
class UriSpecificChannel(uri: String) extends ChannelImpl(uri)
@Singleton class UriChannel extends ChannelManagerImpl("uri", (uri: String) => new UriSpecificChannel(uri))



