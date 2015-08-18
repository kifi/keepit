package com.keepit.notify.info

import com.keepit.common.db.Id
import com.keepit.model.{ Keep, Library, User }
import com.keepit.notify.model.NotificationEvent
import play.api.libs.functional.{ ~, FunctionalCanBuild }

import scala.concurrent.{ ExecutionContext, Future }

sealed trait NeedsInfo[A] {
  val arg: String
}

trait PossibleNeeds {

  case class NeedUser(id: Id[User], arg: String) extends NeedsInfo[User]
  case class NeedLibrary(id: Id[Library], arg: String) extends NeedsInfo[Library]
  case class NeedUserImage(id: Id[User], arg: String) extends NeedsInfo[String]
  case class NeedKeep(id: Id[Keep], arg: String) extends NeedsInfo[Keep]

  def user(id: Id[User], arg: String = ""): NeedsInfo[User] = NeedUser(id, arg)
  def library(id: Id[Library], arg: String = ""): NeedsInfo[Library] = NeedLibrary(id, arg)
  def userImage(id: Id[User], arg: String = ""): NeedsInfo[String] = NeedUserImage(id, arg)
  def keep(id: Id[Keep], arg: String = ""): NeedsInfo[Keep] = NeedKeep(id, arg)

}

trait UsingDsl {

  /**
   * Represents something that produces a series of [[NeedsInfo]] requests from an event [[E]], then consumes
   * them with a function that takes a result [[L]].
   */
  trait Using[E <: NotificationEvent, L <: NeedsInfoList, R] {
    val getInfo: E => L
    val fn: R => NotificationInfo
  }

  case class UsingAll[E <: NotificationEvent, L <: NeedsInfoList](
    getInfo: E => L,
    fn: Seq[Fetched[E, L#AsResultList]] => NotificationInfo) extends Using[E, L, Seq[Fetched[E, L#AsResultList]]]

  case class UsingOne[E <: NotificationEvent, L <: NeedsInfoList](
    getInfo: E => L,
    fn: Fetched[E, L#AsResultList] => NotificationInfo) extends Using[E, L, Fetched[E, L#AsResultList]]

  /**
   * The type inferencer can infer the type of the product (thankfully, because writing it out would be unpleasant),
   * but cannot infer the type of the notification event. Having both type parameters without a higher kind would force
   * writing the type of the product, therefore several methods are placed under an anonymous type, after the event type
   * has been given explicitly.
   */
  def from[E <: NotificationEvent] = new {
    def usingAll[L <: NeedsInfoList](getInfo: E => L)(fn: Seq[Fetched[E, L#AsResultList]] => NotificationInfo) = UsingAll[E, L](getInfo, fn)
    def usingOne[L <: NeedsInfoList](getInfo: E => L)(fn: Fetched[E, L#AsResultList] => NotificationInfo) = UsingOne[E, L](getInfo, fn)
  }

  case class Fetched[+E <: NotificationEvent, L <: ResultList](results: L, originalEvent: E, originalEvents: Seq[E])

  object Results {
    def unapply[E <: NotificationEvent, L <: ResultList](that: Fetched[E, L]): Option[L] = Some(that.results)
  }

  case class Filled[A](elem: A, arg: String = "") extends NeedsInfo[A]

  object get {
    def unapply[A](that: NeedsInfo[A]): Option[A] = Option(that).collect {
      case Filled(elem, arg) => elem
    }
  }

  trait FutureTransform {
    def transform[A](that: NeedsInfo[A]): Future[A]
  }

  trait NeedsInfoList {
    type AsResultList <: ResultList
    def futureMap(f: FutureTransform)(implicit ec: ExecutionContext): Future[AsResultList]
  }

  case object NINil extends NeedsInfoList {
    type AsResultList = ResultNil.type
    def ::[A](that: NeedsInfo[A]) = NICons(that, this)
    def futureMap(f: FutureTransform)(implicit ex: ExecutionContext): Future[ResultNil.type] = Future.successful(ResultNil)
  }
  type NINil = NINil.type

  case class NICons[H, T <: NeedsInfoList](needs: NeedsInfo[H], tail: T) extends NeedsInfoList {
    type AsResultList = ::[H, tail.AsResultList]
    def ::[A](that: NeedsInfo[A]) = NICons(that, this)
    def futureMap(f: FutureTransform)(implicit ec: ExecutionContext): Future[H :: tail.AsResultList] = tail.futureMap(f).flatMap { t =>
      f.transform(needs).map { need =>
        new ::(need, t)
      }
    }
  }

  trait ResultList
  case object ResultNil extends ResultList
  case class ::[H, T](head: H, tail: T) extends ResultList

}

object NeedsInfo extends PossibleNeeds with UsingDsl

