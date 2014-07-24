package com.keepit.common.social

import com.google.inject.{ Inject, Provides, Singleton }
import com.keepit.commanders.UserCommander
import com.keepit.common.akka.MonitoredAwait
import com.keepit.common.concurrent.ExecutionContext
import com.keepit.common.controller.{ ActionAuthenticator, AuthenticatedRequest, ReportedException }
import com.keepit.common.db.slick.Database
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.healthcheck.{ AirbrakeError, AirbrakeNotifier }
import com.keepit.common.logging.Logging
import com.keepit.model.{ UserRepo, ExperimentType, KifiInstallation, User }
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.social._
import net.codingwell.scalaguice.ScalaModule
import play.api.mvc._
import securesocial.core._

import scala.concurrent.Future
import scala.concurrent.duration._

case class TestShoeboxAppSecureSocialModule() extends ShoeboxSecureSocialModule {
  // This has a Play Application dependency.
  // If possible, use `TestActionAuthenticator`! See https://team42.atlassian.net/wiki/display/ENG/Testing+at+FortyTwo
  override def configure(): Unit = {
    import play.api.Play.current
    new SecureSocialUserService().onStart()
    require(UserService.delegate.isDefined)
    install(FakeSocialGraphModule())
  }

  @Singleton
  @Provides
  def secureSocialClientIds: SecureSocialClientIds = SecureSocialClientIds("ovlhms1y0fjr", "530357056981814")
}

case class FakeAuthenticator() extends ScalaModule {
  override def configure(): Unit = {
    bind[ActionAuthenticator].to[TestActionAuthenticator]
  }

  @Singleton
  @Provides
  def secureSocialClientIds: SecureSocialClientIds = SecureSocialClientIds("ovlhms1y0fjr", "530357056981814")
}

@Singleton
class TestActionAuthenticator @Inject() (
  airbrake: AirbrakeNotifier,
  shoeboxClient: ShoeboxServiceClient,
  userRepo: UserRepo,
  db: Database,
  monitoredAwait: MonitoredAwait)
    extends ActionAuthenticator with SecureSocial with Logging {

  implicit private[this] val executionContext = ExecutionContext.immediate

  private def authenticatedHandler[T](userId: Id[User], apiClient: Boolean, allowPending: Boolean)(authAction: AuthenticatedRequest[T] => Future[SimpleResult]): (Request[T] => Future[SimpleResult]) = { implicit request: Request[T] => /* onAuthenticated */
    val experiments = Set.empty[ExperimentType] // todo: allow passing of experiments by a header
    val kifiInstallationId: Option[ExternalId[KifiInstallation]] = None // todo: allow passing of inst id by header

    val user = db.readOnlyMaster(userRepo.get(userId)(_))
    val identity = UserIdentity(Some(userId), SocialUser(IdentityId("username", "fortytwo"), "First", "Last", "First Last", None, None, AuthenticationMethod("userpass"), None, None, None))

    try {
      authAction(AuthenticatedRequest[T](identity, userId, user, request, experiments, kifiInstallationId, None))
    } catch {
      case e: Throwable =>
        throw ReportedException(ExternalId[AirbrakeError](), e)
    }
  }

  def authenticatedAction[T](
    apiClient: Boolean,
    allowPending: Boolean,
    bodyParser: BodyParser[T],
    onAuthenticated: AuthenticatedRequest[T] => Future[SimpleResult],
    onSocialAuthenticated: SecuredRequest[T] => Future[SimpleResult],
    onUnauthenticated: Request[T] => Future[SimpleResult]): Action[T] = Action.async(bodyParser) { request =>
    val userIdOpt = request.headers.get("userId").map(i => Id[User](i.toLong))
    val socialUserOpt = request.headers.get("socialUser").map { _ =>
      SocialUser(IdentityId("username", "fortytwo"), "First", "Last", "First Last", None, None, AuthenticationMethod("userpass"), None, None, None)
    }

    userIdOpt match {
      case Some(uid) =>
        authenticatedHandler(uid, apiClient, allowPending)(onAuthenticated)(request)
      case None if socialUserOpt.nonEmpty =>
        onSocialAuthenticated(SecuredRequest(socialUserOpt.get, request))
      case None =>
        onUnauthenticated(request)
    }
  }

  def isAdmin(userId: Id[User]) = false

}
