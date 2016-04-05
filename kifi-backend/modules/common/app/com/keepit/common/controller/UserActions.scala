package com.keepit.common.controller

import com.keepit.common.controller.FortyTwoCookies.{ ImpersonateCookie, KifiInstallationCookie }
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.healthcheck.{ AirbrakeNotifier, AirbrakeError }
import com.keepit.common.logging.Logging
import com.keepit.common.core._
import com.keepit.common.net.URI
import com.keepit.model.view.UserSessionView
import com.keepit.model.{ UserExperimentType, KifiInstallation, User }
import com.keepit.shoebox.model.ids.UserSessionExternalId
import play.api.Play
import play.api.libs.iteratee.Iteratee
import play.api.mvc._

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import securesocial.core._
import scala.concurrent.duration._
import scala.concurrent.{ Promise, Await, Future }
import scala.util.{ Failure, Success, Try }

sealed trait MaybeUserRequest[T] extends Request[T] {
  // for backward compatibility only; use UserRequest/NonUserRequest where possible
  def userIdOpt: Option[Id[User]] = this match {
    case ur: UserRequest[T] => Some(ur.userId)
    case _ => None
  }
  def userOpt: Option[User] = this match {
    case ur: UserRequest[T] => Some(ur.user)
    case _ => None
  }
  def identityId: Option[IdentityId]
}

case class NonUserRequest[T](request: Request[T], private val getIdentityId: () => Option[IdentityId]) extends WrappedRequest[T](request) with MaybeUserRequest[T] {
  def identityId: Option[IdentityId] = getIdentityId()
}

case class UserRequest[T](request: Request[T], userId: Id[User], adminUserId: Option[Id[User]], helper: UserActionsHelper) extends WrappedRequest[T](request) with MaybeUserRequest[T] with MaybeCostlyUserAttributes[T] {
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

  private val experiments0: Lazily[Set[UserExperimentType]] = new Lazily(helper.getUserExperiments(userId))
  def experimentsF = experiments0.get
  def experiments = experiments0.awaitGet

  def identityId = helper.getIdentityIdFromRequestBlocking(request)

  lazy val kifiInstallationId: Option[ExternalId[KifiInstallation]] = helper.getKifiInstallationIdOpt
}

// for backward-compatibility
trait MaybeCostlyUserAttributes[T] { self: UserRequest[T] =>
  def user: User
  def experiments: Set[UserExperimentType]
  def kifiInstallationId: Option[ExternalId[KifiInstallation]]
}

import KifiSession._

trait UserActionsHelper extends Logging {

  def airbrake: AirbrakeNotifier

  def buildNonUserRequest[A](implicit request: Request[A]): NonUserRequest[A] = NonUserRequest(request, () => getIdentityIdFromRequestBlocking(request))

  def kifiInstallationCookie: KifiInstallationCookie

  def impersonateCookie: ImpersonateCookie

  def isAdmin(userId: Id[User]): Future[Boolean]

  def getUserOpt(userId: Id[User]): Future[Option[User]]

  def getUserByExtIdOpt(extId: ExternalId[User]): Future[Option[User]]

  def getUserExperiments(userId: Id[User]): Future[Set[UserExperimentType]]

  def getSessionByExternalId(sessionId: UserSessionExternalId): Future[Option[UserSessionView]]

  def getUserIdOptWithFallback(implicit request: RequestHeader): Future[Option[Id[User]]]

  ///////////////////////////////////////////////////
  // Implementations based on above used elsewhere:

  def getSessionIdFromRequest(request: RequestHeader): Option[UserSessionExternalId] = {
    // Unsure if `sid` is used anymore, or even is a good idea. Cookie name needs to be SecureSocial's `Authenticator.cookieName`
    request.cookies.get("KIFI_SECURESOCIAL").map(_.value).orElse(request.queryString.get("sid").flatMap(_.headOption)).filter(_.nonEmpty).map(UserSessionExternalId(_))
  }

  def getIdentityIdFromRequestBlocking(implicit request: RequestHeader): Option[IdentityId] = {
    getSessionIdFromRequest(request) flatMap { sessionId =>
      val idF = getSessionByExternalId(sessionId).map {
        case Some(session) =>
          Some(IdentityId(session.socialId.id, session.provider.name))
        case None => None
      }
      Await.result(idF, 10.seconds)
    }
  }

  def getUserIdFromSession(implicit request: RequestHeader): Try[Option[Id[User]]] =
    Try {
      Play.maybeApplication.flatMap { _ => request.session.getUserId }
    }

  def getKifiInstallationIdOpt(implicit request: RequestHeader): Option[ExternalId[KifiInstallation]] = {
    kifiInstallationCookie.decodeFromCookie(request.cookies.get(kifiInstallationCookie.COOKIE_NAME))
  }

  def getImpersonatedUserIdOpt(implicit request: RequestHeader): Option[ExternalId[User]] = {
    impersonateCookie.decodeFromCookie(request.cookies.get(impersonateCookie.COOKIE_NAME))
  }

}

trait UserActions extends Logging { self: Controller =>

  protected def userActionsHelper: UserActionsHelper

  private def buildUserRequest[A](userId: Id[User], adminUserId: Option[Id[User]] = None)(implicit request: Request[A]): UserRequest[A] =
    UserRequest(request, userId, adminUserId, userActionsHelper)

  private def maybeSetUserIdInSession[A](userId: Id[User], res: Result)(implicit request: Request[A]): Result = {
    Play.maybeApplication.map { app =>
      userActionsHelper.getUserIdFromSession(request) match {
        case Success(Some(id)) if id == userId => res
        case Success(_) =>
          res.withSession(res.session.setUserId(userId))
        case Failure(t) =>
          log.error(s"[maybeSetUserIdInSession($userId)] Caught exception while retrieving userId from kifi cookie", t)
          res.withSession(res.session.setUserId(userId))
      }
    } getOrElse res
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
    userActionsHelper.getImpersonatedUserIdOpt match {
      case Some(impExtId) =>
        impersonate(userId, impExtId).flatMap { userRequest =>
          block(userRequest).map(maybeSetUserIdInSession(userId, _))
        }
      case None =>
        block(buildUserRequest(userId)).map(maybeSetUserIdInSession(userId, _))
    }
  }

  object UserAction extends ActionBuilder[UserRequest] {
    def invokeBlock[A](request: Request[A], block: (UserRequest[A]) => Future[Result]): Future[Result] = {
      implicit val req = request
      val result = userActionsHelper.getUserIdOptWithFallback.flatMap { userIdOpt =>
        userIdOpt match {
          case Some(userId) => buildUserAction(userId, block)
          case None => Future.successful(Forbidden)
        }
      }
      result.map(r => HeaderAugmentor.process(r))
    }
  }

  object UserPage extends ActionBuilder[UserRequest] {
    def invokeBlock[A](request: Request[A], block: (UserRequest[A]) => Future[Result]) = {
      implicit val req = request
      UserAction.invokeBlock(request, block).map {
        case result if result.header.status == FORBIDDEN =>
          val nRes = Redirect("/login")
          // Less than ideal, but we can't currently test this:
          Play.maybeApplication match {
            case Some(_) => nRes.withSession(result.session + (SecureSocial.OriginalUrlKey -> request.uri))
            case None => nRes
          }
        case result =>
          result
      }
    }
  }

  object MaybeUserAction extends ActionBuilder[MaybeUserRequest] {
    def invokeBlock[A](request: Request[A], block: (MaybeUserRequest[A]) => Future[Result]): Future[Result] = {
      implicit val req = request
      val result = userActionsHelper.getUserIdOptWithFallback.flatMap { userIdOpt =>
        userIdOpt match {
          case Some(userId) => buildUserAction(userId, block)
          case None => block(userActionsHelper.buildNonUserRequest)
        }
      }
      result.map(r => HeaderAugmentor.process(r))
    }
  }

  val MaybeUserPage = MaybeUserAction // currently we don't have situations when Forbidden means they need to log in for a MaybeUserPage

}

trait AdminUserActions extends UserActions with ShoeboxServiceController {

  private object AdminCheck extends ActionFilter[UserRequest] {
    protected def filter[A](request: UserRequest[A]): Future[Option[Result]] = {
      if (request.adminUserId.exists(id => Await.result(userActionsHelper.isAdmin(id), 5 seconds))
        || (Play.maybeApplication.exists(Play.isDev(_) && request.userId.id == 1L))) Future.successful(None)
      else userActionsHelper.isAdmin(request.userId).map { isAdmin =>
        if (isAdmin) None
        else Some(Forbidden) tap { res => log.warn(s"[AdminCheck] User ${request.userId} is denied access to ${request.path}") }
      }
    }
  }

  val AdminUserAction = UserAction andThen AdminCheck
  val AdminUserPage = AdminUserAction // not forwarding user when the hit an admin page when not logged in is okay

}

object KifiSession {

  val FORTYTWO_USER_ID = "fortytwo_user_id"

  implicit class HttpSessionWrapper(val underlying: Session) extends AnyVal {
    def setUserId(userId: Id[User]): Session = underlying + (FORTYTWO_USER_ID -> userId.id.toString)
    def getUserId(): Option[Id[User]] = underlying.get(FORTYTWO_USER_ID) map parseUserId
    def parseUserId(id: String): Id[User] = {
      val idInt = try {
        id.toLong
      } catch {
        //There's some bad sessions out there that contains the id in the format of "Some(1234)" instead of just "1234". This is an attempt to let these ids through in spite of the bad formatting.
        case e: NumberFormatException =>
          """Some\(([0-9]+)\)""".r.findAllMatchIn(id).map(_.group(1)).toSeq.headOption.map(_.toLong).getOrElse(throw new Exception(s"can't parse id $id", e))
      }
      Id[User](idInt)
    }
    def deleteUserId(): Session = underlying - FORTYTWO_USER_ID
  }

}

case class ReportedException(id: ExternalId[AirbrakeError], cause: Throwable) extends Exception(id.toString, cause)
