package com.keepit.notify.model

import com.keepit.common.db.Id

/**
 * A shortcut to grouping events quickly. If the group identifier function returns Some for a notification kind,
 * then a new event of that kind will automatically be grouped with the notification with that identifier.
 *
 * Typically grouping is more intelligent and requires reading a couple events from the database and deserializing
 * JSON. For events which can be grouped with other events far earlier, deserializing a whole bunch
 * of events from the database to find the right group can be expensive. In addition, events like these do not require
 * advanced grouping behavior and only rely on a few external ids. Therefore, using [[GroupIdentifier]] only requires
 * a simple WHERE sql clause on the notification table instead of a whole bunch of deserialization.
 */
trait GroupIdentifier[A] { self =>

  def deserialize(str: String): A
  def serialize(that: A): String

  def inmap[B](f1: A => B, f2: B => A): GroupIdentifier[B] = new GroupIdentifier[B] {
    def serialize(that: B): String = self.serialize(f2(that))
    def deserialize(str: String): B = f1(self.deserialize(str))
  }

}

object GroupIdentifier {

  def apply[A](implicit id: GroupIdentifier[A]): GroupIdentifier[A] = id

  implicit def tuple2GroupIdentifier[A, B](implicit aGid: GroupIdentifier[A], bGid: GroupIdentifier[B]): GroupIdentifier[(A, B)] =
    new GroupIdentifier[(A, B)] {
      def serialize(that: (A, B)): String = that match {
        case (a, b) => aGid.serialize(a) + ":" + bGid.serialize(b)
      }
      def deserialize(str: String): (A, B) = {
        val Array(a, b) = str.split(":")
        (aGid.deserialize(a), bGid.deserialize(b))
      }
    }

  implicit def longGroupIdentifier: GroupIdentifier[Long] = new GroupIdentifier[Long] {
    override def serialize(that: Long): String = that.toString
    override def deserialize(str: String): Long = str.toLong
  }

  implicit def idGroupIdentifier[A]: GroupIdentifier[Id[A]] = GroupIdentifier[Long].inmap(Id(_), _.id)

}
