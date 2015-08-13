package com.keepit.notify.info

import com.keepit.common.db.Id
import com.keepit.common.path.EncodedPath
import com.keepit.model.{ LibrarySlug, Library, User }
import com.keepit.notify.model.NotificationEvent

import scala.concurrent.Future

/**
 * Represents a monad that essentially wraps [[Future]] and [[InfoResult]], representing the method by which notification
 * events can obtain notification information. The reason this exists is because notification information can be generated
 * on the shoebox side or the eliza side, when the event is generated or when it is emitted, respectively. In order to
 * prevent unnecessary requests across services, information associated with the notification event is cached when the
 * event is first built and sent to Eliza, where Eliza can decide to keep it if it send the notification immediately.
 */
sealed trait ReturnsInfo[+A] {

  def map[B](that: A => B): ReturnsInfo[B] = AndThen(this, (a: A) =>
    Returns(that(a))
  )

  def flatMap[B](that: A => ReturnsInfo[B]): ReturnsInfo[B] = AndThen(this, that)

  def filter(f: A => Boolean): ReturnsInfo[A] = AndThen(this, (a: A) =>
    if (f(a)) {
      Returns(a)
    } else Fails(new NoSuchElementException("ReturnsInfo.filter predicate failed"))
  )

}

case class Returns[A](a: A) extends ReturnsInfo[A]
case class Fails(e: Throwable) extends ReturnsInfo[Nothing]
case class AndThen[A, B](previous: ReturnsInfo[A], f: A => ReturnsInfo[B]) extends ReturnsInfo[B]

trait ArgAction[+A] extends ReturnsInfo[A] {

  val argName: String

  def fromSource(source: NotificationInfoSource): Future[A]

  def arg(newArgName: String): this.type

}

object ArgAction {

  def unapply[A](that: ArgAction[A]): Option[(String, NotificationInfoSource => Future[A])] = Some(
    that.argName,
    that.fromSource _
  )

}

object ReturnsInfo {

  case class PickOne[E <: NotificationEvent](events: Set[E]) extends ReturnsInfo[E]

  case class GetUser(id: Id[User], argName: String = "") extends ArgAction[User] {
    def fromSource(source: NotificationInfoSource) = source.user(id)

    def arg(newArgName: String) = copy(argName = newArgName)
  }

  case class GetLibrary(id: Id[Library], argName: String = "") extends ArgAction[Library] {
    def fromSource(source: NotificationInfoSource) = source.library(id)

    def arg(newArgName: String) = copy(argName = newArgName)
  }

  case class GetUserImage(id: Id[User], width: Int, argName: String = "") extends ArgAction[String] {
    def fromSource(source: NotificationInfoSource) = source.userImage(id, width)

    def arg(newArgName: String) = copy(argName = newArgName)
  }

  case class GetLibraryPath(id: Id[Library], argName: String = "") extends ArgAction[EncodedPath] {
    def fromSource(source: NotificationInfoSource) = source.libraryPath(id)

    def arg(newArgName: String) = copy(argName = newArgName)
  }

}
