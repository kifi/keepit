package com.keepit.common.controller

import com.keepit.common.controller.FortyTwoCookies.{ ImpersonateCookie, KifiInstallationCookie }
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.logging.Logging
import com.keepit.common.core._
import com.keepit.common.net.URI
import com.keepit.model.{ ExperimentType, KifiInstallation, User }
import play.api.Play
import play.api.mvc._

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import securesocial.core.Identity
import scala.concurrent.duration._
import scala.concurrent.{ Promise, Await, Future }

sealed trait MaybeUserRequest[T] extends Request[T]

case class NonUserRequest[T](request: Request[T], private val identityF: () => Option[Identity] = () => None) extends WrappedRequest[T](request) with MaybeUserRequest[T] with SecureSocialIdentityAccess[T] {
  def identityOpt: Option[Identity] = identityF.apply
}

case class UserRequest[T](request: Request[T], userId: Id[User], adminUserId: Option[Id[User]], helper: UserActionsHelper) extends WrappedRequest[T](request) with MaybeUserRequest[T] with SecureSocialIdentityAccess[T] with MaybeCostlyUserAttributes[T] {
  implicit val req = request

  private class Lazily[A](f: => Future[A]) {
    private lazy val cachedF: Future[A] = f
    private lazy val cachedV = Await.result(cachedF, 5 seconds)
    def get: Future[A] = cachedF
    def awaitGet: A = cachedV
  }

  private val user0: Lazily[User] = new Lazily(helper.getUserOpt(userId).map(_.get))
  def userF = user0.get
  def user = user0.awaitGet

  private val experiments0: Lazily[Set[ExperimentType]] = new Lazily(helper.getUserExperiments(userId))
  def experimentsF = experiments0.get
  def experiments = experiments0.awaitGet

  private val identityOpt0: Lazily[Option[Identity]] = new Lazily(helper.getSecureSocialIdentityOpt(userId))
  def identityOptF = identityOpt0.get
  def identityOpt = identityOpt0.awaitGet

  lazy val kifiInstallationId: Option[ExternalId[KifiInstallation]] = helper.getKifiInstallationIdOpt
}

// for backward-compatibility
trait MaybeCostlyUserAttributes[T] { self: UserRequest[T] =>
  def user: User
  def experiments: Set[ExperimentType]
  def kifiInstallationId: Option[ExternalId[KifiInstallation]]
}

// for backward-compatibility
trait SecureSocialIdentityAccess[T] { self: MaybeUserRequest[T] =>
  def identityOpt: Option[Identity]
}

trait UserActionsRequirements {

  def buildNonUserRequest[A](implicit request: Request[A]): NonUserRequest[A] = NonUserRequest(request)

  def kifiInstallationCookie: KifiInstallationCookie

  def impersonateCookie: ImpersonateCookie

  def isAdmin(userId: Id[User])(implicit request: Request[_]): Future[Boolean]

  def getUserOpt(userId: Id[User])(implicit request: Request[_]): Future[Option[User]]

  def getUserByExtIdOpt(extId: ExternalId[User]): Future[Option[User]]

  def getUserExperiments(userId: Id[User])(implicit request: Request[_]): Future[Set[ExperimentType]]

  def getSecureSocialIdentityOpt(userId: Id[User])(implicit request: Request[_]): Future[Option[Identity]]

}

trait UserActionsHelper extends UserActionsRequirements {

  def getUserIdOpt(implicit request: Request[_]): Option[Id[User]] = {
    request.session.get(ActionAuthenticator.FORTYTWO_USER_ID).map(id => Id[User](id.toLong)) // check with mobile
  }

  def getKifiInstallationIdOpt(implicit request: Request[_]): Option[ExternalId[KifiInstallation]] = {
    kifiInstallationCookie.decodeFromCookie(request.cookies.get(kifiInstallationCookie.COOKIE_NAME))
  }

  def getImpersonatedUserIdOpt(implicit request: Request[_]): Option[ExternalId[User]] = {
    impersonateCookie.decodeFromCookie(request.cookies.get(impersonateCookie.COOKIE_NAME))
  }

}

trait UserActions extends Logging { self: Controller =>

  protected def userActionsHelper: UserActionsHelper

  private def buildUserRequest[A](userId: Id[User], adminUserId: Option[Id[User]] = None)(implicit request: Request[A]): UserRequest[A] =
    UserRequest(request, userId, adminUserId, userActionsHelper)

  private def maybeAugmentCORS[A](res: Result)(implicit request: Request[A]): Result = {
    request.headers.get("Origin").filter { uri =>
      val host = URI.parse(uri).toOption.flatMap(_.host).map(_.toString).getOrElse("")
      host.endsWith("ezkeep.com") || host.endsWith("kifi.com")
    } map { h =>
      res.withHeaders(
        "Access-Control-Allow-Origin" -> h,
        "Access-Control-Allow-Credentials" -> "true"
      )
    } getOrElse res
  }

  private def maybeAugmentKcid[A](res: Result)(implicit request: Request[A]): Result = { // for campaign tracking
    request.queryString.get("kcid").flatMap(_.headOption).map { kcid =>
      res.addingToSession("kcid" -> kcid)(request)
    } getOrElse res
  }

  private def maybeSetUserIdInSession[A](userId: Id[User], res: Result)(implicit request: Request[A]): Result = {
    userActionsHelper.getUserIdOpt(request) match {
      case Some(id) if (id == userId) => res
      case _ => res.withSession(request.session + (ActionAuthenticator.FORTYTWO_USER_ID -> userId.toString))
    }
  }

  private def impersonate[A](adminUserId: Id[User], impersonateExtId: ExternalId[User])(implicit request: Request[A]): Future[UserRequest[A]] = {
    userActionsHelper.isAdmin(adminUserId) flatMap { isAdmin =>
      if (!isAdmin) throw new IllegalStateException(s"non admin user $adminUserId tries to impersonate to $impersonateExtId")
      userActionsHelper.getUserByExtIdOpt(impersonateExtId) map { impUserOpt =>
        val impUserId = impUserOpt.get.id.get // fail hard
        log.info(s"[impersonate] admin user $adminUserId is impersonating user $impUserId with request ${request.path}")
        buildUserRequest(impUserId, Some(adminUserId))
      }
    }
  }

  private def buildUserAction[A](userId: Id[User], block: (UserRequest[A]) => Future[Result])(implicit request: Request[A]): Future[Result] = {
    val resF = userActionsHelper.getImpersonatedUserIdOpt match {
      case Some(impExtId) =>
        impersonate(userId, impExtId).flatMap { req =>
          block(req).map(maybeSetUserIdInSession(userId, _))
        }
      case None =>
        block(buildUserRequest(userId)).map(maybeSetUserIdInSession(userId, _))
    }
    resF.map(maybeAugmentCORS(_))
  }

  protected def PageAction[P[_]] = new ActionFunction[P, P] {
    def invokeBlock[A](request: P[A], block: (P[A]) => Future[Result]): Future[Result] = {
      block(request) // todo(ray): handle error with redirects
    }
  }

  object UserAction extends ActionBuilder[UserRequest] {
    def invokeBlock[A](request: Request[A], block: (UserRequest[A]) => Future[Result]): Future[Result] = {
      implicit val req = request
      userActionsHelper.getUserIdOpt match {
        case Some(userId) => buildUserAction(userId, block)
        case None => Future.successful(Forbidden) tap { _ => log.warn(s"[UserAction] Failed to retrieve userId for request=$request; headers=${request.headers.toMap}") }
      }
    }
  }
  val UserPage = (UserAction andThen PageAction)

  object MaybeUserAction extends ActionBuilder[MaybeUserRequest] {
    def invokeBlock[A](request: Request[A], block: (MaybeUserRequest[A]) => Future[Result]): Future[Result] = {
      implicit val req = request
      userActionsHelper.getUserIdOpt match {
        case Some(userId) => buildUserAction(userId, block)
        case None => block(userActionsHelper.buildNonUserRequest).map(maybeAugmentKcid(_))
      }
    }
  }
  val MaybeUserPage = (MaybeUserAction andThen PageAction)

}

trait AdminUserActions extends UserActions with ShoeboxServiceController {

  private object AdminCheck extends ActionFilter[UserRequest] {
    protected def filter[A](request: UserRequest[A]): Future[Option[Result]] = {
      if (request.adminUserId.exists(id => Await.result(userActionsHelper.isAdmin(id)(request), 5 seconds))
        || (Play.maybeApplication.exists(Play.isDev(_) && request.userId.id == 1L))) Future.successful(None)
      else userActionsHelper.isAdmin(request.userId)(request) map { isAdmin =>
        if (isAdmin) None
        else Some(Forbidden) tap { res => log.warn(s"[AdminCheck] User ${request.userId} is denied access to ${request.path}") }
      }
    }
  }

  val AdminUserAction = (UserAction andThen AdminCheck)
  val AdminUserPage = (AdminUserAction andThen PageAction)

}
