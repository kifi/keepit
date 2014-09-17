package com.keepit.common.controller

import com.google.inject.{ Inject, Singleton }
import com.keepit.commanders.UserExperimentCommander
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.model.{ KifiInstallation, ExperimentType, User }
import play.api.mvc._
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

import scala.concurrent.{ Await, Future }

sealed trait MaybeUserRequest[T]
trait NonUserRequest[T] extends MaybeUserRequest[T]
trait PartialNonUserRequest[T] extends NonUserRequest[T] // OAuth successful but no User (sign-up)
trait UserRequest[T] extends MaybeUserRequest[T] {
  def userId: Id[User]
  def userF: Future[User]
  def user: User
  def experimentsF: Future[Set[ExperimentType]]
  def experiments: Set[ExperimentType]
  def kifiInstallationId: Option[ExternalId[KifiInstallation]]
  def adminUserId: Option[Id[User]]
}

case class SimpleNonUserRequest[T](request: Request[T]) extends WrappedRequest(request) with NonUserRequest[T]
case class SimplePartialNonUserRequest[T](request: Request[T]) extends WrappedRequest(request) with PartialNonUserRequest[T]
case class SimpleUserRequest[T](
    userId: Id[User],
    userF: Future[User],
    experimentsF: Future[Set[ExperimentType]],
    kifiInstallationId: Option[ExternalId[KifiInstallation]] = None,
    adminUserId: Option[Id[User]] = None,
    request: Request[T]) extends WrappedRequest(request) with UserRequest[T] {
  lazy val user = Await.result(userF, 5 seconds)
  lazy val experiments = Await.result(experimentsF, 5 seconds)
}

trait UserActionsHelper {
  def getUserIdOpt(implicit request: Request[_]): Option[Id[User]] = {
    request.session.get(ActionAuthenticator.FORTYTWO_USER_ID).map(id => Id[User](id.toLong)) // check with mobile
  }

  def isAdmin(userId: Id[User]): Boolean

  def getUserOpt(implicit request: Request[_]): Future[Option[User]]

  def getUserExperiments(implicit request: Request[_]): Future[Set[ExperimentType]]
}

trait UserActions { self: Controller =>

  protected def userActionsHelper: UserActionsHelper

  object UserAction extends ActionBuilder[UserRequest] {
    def invokeBlock[A](request: Request[A], block: (UserRequest[A]) => Future[Result]): Future[Result] = {
      implicit val req = request
      userActionsHelper.getUserIdOpt match {
        case Some(userId) =>
          println(s"[invokeBlock] userId=$userId")
          block(SimpleUserRequest(userId, userActionsHelper.getUserOpt.map(_.get), userActionsHelper.getUserExperiments, None, None, request))
        case None => Future.successful(Unauthorized)
      }
    }
  }

  object MaybeUserAction extends ActionBuilder[MaybeUserRequest] {
    def invokeBlock[A](request: Request[A], block: (MaybeUserRequest[A]) => Future[Result]): Future[Result] = {
      implicit val req = request
      userActionsHelper.getUserIdOpt match {
        case Some(userId) => block(SimpleUserRequest(userId, userActionsHelper.getUserOpt.map(_.get), userActionsHelper.getUserExperiments, None, None, request))
        case None => block(SimpleNonUserRequest(request))
      }
    }
  }

}
