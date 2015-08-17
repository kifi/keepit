package com.keepit.notify.info

import com.keepit.common.db.Id
import com.keepit.model.{ Library, User }
import com.keepit.notify.model.NotificationEvent

sealed trait NeedsInfo[A] {
  val arg: String
}

trait PossibleNeeds {

  case class NeedUser(id: Id[User], arg: String) extends NeedsInfo[User]
  case class NeedLibrary(id: Id[Library], arg: String) extends NeedsInfo[Library]
  case class NeedUserImage(id: Id[User], arg: String) extends NeedsInfo[String]

  def user(id: Id[User], arg: String = ""): NeedsInfo[User] = NeedUser(id, arg)
  def library(id: Id[Library], arg: String = ""): NeedsInfo[Library] = NeedLibrary(id, arg)
  def userImage(id: Id[User], arg: String = ""): NeedsInfo[String] = NeedUserImage(id, arg)

}

trait UsingDsl {

  /**
   * Represents something that produces a series of [[NeedsInfo]] requests [[P]] from an event [[E]], then consumes
   * them with a function that takes a result [[R]].
   */
  trait Using[E <: NotificationEvent, P <: Product, R] {
    val getInfo: E => P
    val fn: R => NotificationInfo
  }

  /**
   * Represents a [[Using]] that uses all notification events given.
   */
  case class UsingAll[E <: NotificationEvent, P <: Product](
    getInfo: E => P,
    fn: Seq[Result[E, P]] => NotificationInfo) extends Using[E, P, Seq[Result[E, P]]]

  /**
   * Represents a [[Using]] that uses only one notification event given.
   */
  case class UsingOne[E <: NotificationEvent, P <: Product](
    getInfo: E => P,
    fn: Result[E, P] => NotificationInfo) extends Using[E, P, Result[E, P]]

  /**
   * The type inferencer can infer the type of the product (thankfully, because writing it out would be unpleasant),
   * but cannot infer the type of the notification event. Having both type parameters without a higher kind would force
   * writing the type of the product, therefore several methods are placed under an anonymous type, after the event type
   * has been given explicitly.
   */
  def event[E <: NotificationEvent] = new {
    def usingAll[P <: Product](getInfo: E => P)(fn: Seq[Result[E, P]] => NotificationInfo) = UsingAll[E, P](getInfo, fn)
    def usingOne[P <: Product](getInfo: E => P)(fn: Result[E, P] => NotificationInfo) = UsingOne[E, P](getInfo, fn)
  }

  /**
   * The result of a fetch operation. It is assumed that the product type is filled with [[NeedsInfo]] objects.
   */
  class Result[+E <: NotificationEvent, +P <: Product](event: E, p: P)

  /**
   * The result of requesting a [[NeedsInfo]] object, filled with the requested information.
   */
  case class Filled[A](elem: A, arg: String = "") extends NeedsInfo[A]

  /**
   * DSL used to pattern match on the results of the dependencies.
   */
  object result {
    def unapply[E <: NotificationEvent, P <: Product](that: Result[E, P]): Option[(E, P)] = result.unapply(that)
  }

  /**
   * DSL used to pattern match on the individual dependencies that were fetched.
   */
  object get {
    def unapply[A](that: NeedsInfo[A]): Option[A] = Option(that).collect {
      case Filled(elem, arg) => elem
    }
  }

}

object NeedsInfo extends PossibleNeeds with UsingDsl

