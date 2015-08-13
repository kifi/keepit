package com.keepit.notify.info

import com.keepit.common.db.Id
import com.keepit.common.path.EncodedPath
import com.keepit.model.{ LibrarySlug, Library, User }
import com.keepit.notify.model.{ NotificationEvent, NotificationInfo }

import scala.concurrent.Future

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

  val arg: String

  def fromSource(source: NotificationInfoSource): Future[A]

}

object ArgAction {

  def unapply[A](that: ArgAction[A]): Option[(String, NotificationInfoSource => Future[A])] = Some(
    that.arg,
    that.fromSource _
  )

}

object ReturnsInfo {

  case class PickOne[E <: NotificationEvent](events: Set[E]) extends ReturnsInfo[E]

  case class GetUser(id: Id[User], arg: String = "") extends ArgAction[User] {
    def fromSource(source: NotificationInfoSource) = source.user(id)
  }

  case class GetLibrary(id: Id[Library], arg: String = "") extends ArgAction[Library] {
    def fromSource(source: NotificationInfoSource) = source.library(id)
  }

  case class GetUserImage(id: Id[User], width: Int, arg: String = "") extends ArgAction[String] {
    def fromSource(source: NotificationInfoSource) = source.userImage(id, width)
  }

  case class GetLibraryPath(id: Id[Library], arg: String = "") extends ArgAction[EncodedPath] {
    def fromSource(source: NotificationInfoSource) = source.libraryPath(id)
  }

}
