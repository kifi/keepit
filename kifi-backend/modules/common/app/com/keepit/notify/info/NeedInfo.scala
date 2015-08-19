package com.keepit.notify.info

import com.keepit.common.db.Id
import com.keepit.model.{ Keep, Library, User }
import com.keepit.notify.model.{EventArgs, GenEventArgs, NotificationEvent}
import play.api.libs.functional.{ ~, FunctionalCanBuild }

import scala.concurrent.{ ExecutionContext, Future }

sealed trait NeedInfo[+A]

trait PossibleNeeds {

  case class NeedUser(id: Id[User]) extends NeedInfo[User]
  case class NeedLibrary(id: Id[Library]) extends NeedInfo[Library]
  case class NeedUserImageUrl(id: Id[User]) extends NeedInfo[String]
  case class NeedKeep(id: Id[Keep]) extends NeedInfo[Keep]
  case class NeedLibraryUrl(id: Id[Library]) extends NeedInfo[String]

  def user(id: Id[User]): NeedInfo[User] = NeedUser(id)
  def library(id: Id[Library]): NeedInfo[Library] = NeedLibrary(id)
  def userImageUrl(id: Id[User]): NeedInfo[String] = NeedUserImageUrl(id)
  def keep(id: Id[Keep]): NeedInfo[Keep] = NeedKeep(id)
  def libraryUrl(id: Id[Library]): NeedInfo[String] = NeedLibraryUrl(id)

}

trait ArgsDsl {

  implicit class StringGenArgs(str: String) {
    def arg[A, E <: NotificationEvent](f: E => A, need: A => NeedInfo[Any]): GenEventArgs[E] = arg(f andThen need)
    def arg[E <: NotificationEvent](f: E => NeedInfo[Any]): GenEventArgs[E] = GenEventArgs(Seq(str), e => Seq(f(e)))
  }

}

trait UsingDsl {

  /**
   * Represents something that produces a series of [[NeedInfo]] requests from an event [[E]], then consumes
   * them with a function that takes a result.
   */
  trait Using[E <: NotificationEvent, R] {
    val getInfo: GenEventArgs[E]
    val fn: R => NotificationInfo
  }

  case class UsingAll[E <: NotificationEvent](
    getInfo: GenEventArgs[E],
    fn: Seq[Fetched[E]] => NotificationInfo) extends Using[E, Seq[Fetched[E]]]

  case class UsingOne[E <: NotificationEvent](
    getInfo: GenEventArgs[E],
    fn: Fetched[E] => NotificationInfo) extends Using[E, Fetched[E]]

  def usingAll[E <: NotificationEvent](getInfo: GenEventArgs[E]*)(fn: Seq[Fetched[E]] => NotificationInfo) =
    UsingAll[E](GenEventArgs.sequence(getInfo), fn)
  def usingOne[E <: NotificationEvent](getInfo: GenEventArgs[E]*)(fn: Fetched[E] => NotificationInfo) =
    UsingOne[E](GenEventArgs.sequence(getInfo), fn)

  case class Fetched[E <: NotificationEvent](results: EventArgs[E], originalEvents: Seq[E])

  object Results {
    def unapply[E <: NotificationEvent](that: Fetched[E]): Option[EventArgs[E]] = Some(that.results)
  }

  case class Filled[A](elem: A) extends NeedInfo[A]

}

object NeedInfo extends PossibleNeeds with UsingDsl with ArgsDsl

