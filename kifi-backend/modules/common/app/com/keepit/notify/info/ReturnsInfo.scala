package com.keepit.notify.info

import com.keepit.common.db.Id
import com.keepit.common.path.EncodedPath
import com.keepit.model.{ LibrarySlug, Library, User }
import com.keepit.notify.model.{ NotificationEvent, NotificationInfo }

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

trait CachedAction[+A] extends ReturnsInfo[A] {

  val name: String

}

object ReturnsInfo {

  case class PickOne[E <: NotificationEvent](events: Set[E]) extends ReturnsInfo[E]
  case class GetUser(id: Id[User], name: String = "") extends CachedAction[User]
  case class GetLibrary(id: Id[Library], name: String = "") extends CachedAction[Library]
  case class GetUserImage(id: Id[User], name: String = "") extends CachedAction[String]
  case class GetLibraryPath(id: Id[Library], name: String = "") extends CachedAction[EncodedPath]

}
