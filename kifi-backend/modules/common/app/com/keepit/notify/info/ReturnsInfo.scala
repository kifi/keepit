package com.keepit.notify.info

import com.keepit.common.db.Id
import com.keepit.common.path.EncodedPath
import com.keepit.model.{LibrarySlug, Library, User}
import com.keepit.notify.model.NotificationInfo

sealed trait ReturnsInfo[+A] {

  def map[B](that: A => B): ReturnsInfo[B] = AndThen(this) { a =>
    Returns(that(a))
  }

  def flatMap[B](that: A => ReturnsInfo[B]): ReturnsInfo[B] = AndThen(this)(that)

  def filter(f: A => Boolean): ReturnsInfo[A] = AndThen(this) { a =>
    if (f(a)) {
      Returns(a)
    } else Fails(new NoSuchElementException("ReturnsInfo.filter predicate failed"))
  }

}

object ReturnsInfo {

  def user(id: Id[User]) = GetUser(id)
  def library(id: Id[Library]) = GetLibrary(id)

  def libraryPath(id: Id[Library]) = GetLibraryPath(id)

  def userImage(id: Id[User]) = GetUserImage(id)

}

case class Returns[A](a: A) extends ReturnsInfo[A]
case class Fails(e: Throwable) extends ReturnsInfo[Nothing]
case class AndThen[A, B](previous: ReturnsInfo[A])(f: A => ReturnsInfo[B]) extends ReturnsInfo[B]

case class GetUser(id: Id[User]) extends ReturnsInfo[User]
case class GetLibrary(id: Id[Library]) extends ReturnsInfo[User]
case class GetUserImage(id: Id[User]) extends ReturnsInfo[User]
case class GetLibraryPath(id: Id[Library]) extends ReturnsInfo[EncodedPath]
