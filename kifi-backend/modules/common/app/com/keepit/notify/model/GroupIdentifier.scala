package com.keepit.notify.model

import com.keepit.common.db.Id
import play.api.libs.json.{ JsString, JsResult, JsValue, Format }

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

trait GroupIdentifierLowPriorityImplicits {
  implicit def formatForGroupIdentifier[A](gid: GroupIdentifier[A]): Format[A] = new Format[A] {
    def reads(json: JsValue): JsResult[A] = json.validate[JsString].map(s => gid.deserialize(s.value))
    def writes(o: A): JsValue = JsString(gid.serialize(o))
  }
}

object GroupIdentifier extends GroupIdentifierLowPriorityImplicits {

  def apply[A](implicit id: GroupIdentifier[A]): GroupIdentifier[A] = id

  implicit def tuple2GroupIdentifier[A, B](implicit aGid: GroupIdentifier[A], bGid: GroupIdentifier[B]): GroupIdentifier[(A, B)] =
    new GroupIdentifier[(A, B)] {
      def serialize(that: (A, B)): String = that match {
        case (a, b) => aGid.serialize(a) + ":::" + bGid.serialize(b)
      }
      def deserialize(str: String): (A, B) = {
        val Array(a, b) = str.split(":::")
        (aGid.deserialize(a), bGid.deserialize(b))
      }
    }

  implicit val recipientGroupIdentifier: GroupIdentifier[Recipient] = new GroupIdentifier[Recipient] {
    def serialize(that: Recipient): String = Recipient.serialize(that)
    def deserialize(str: String): Recipient = Recipient.deserialize(str).get
  }
  implicit val longGroupIdentifier: GroupIdentifier[Long] = new GroupIdentifier[Long] {
    def serialize(that: Long): String = that.toString
    def deserialize(str: String): Long = str.toLong
  }

  implicit def idGroupIdentifier[A]: GroupIdentifier[Id[A]] = GroupIdentifier[Long].inmap(Id(_), _.id)
}
