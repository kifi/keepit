package com.keepit.common.social

import com.google.inject.{ Inject, Provides, Singleton }
import com.keepit.commanders.UserCommander
import com.keepit.common.akka.MonitoredAwait
import com.keepit.common.concurrent.ExecutionContext
import com.keepit.common.controller.{ FakeActionAuthenticator, ActionAuthenticator, AuthenticatedRequest, ReportedException }
import com.keepit.common.db.slick.Database
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.healthcheck.{ AirbrakeError, AirbrakeNotifier }
import com.keepit.common.logging.Logging
import com.keepit.model.{ UserRepo, ExperimentType, KifiInstallation, User }
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.social._
import com.keepit.social.providers.PasswordAuthentication
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
    bind[PasswordAuthentication].to[UserPasswordAuthentication]
  }

  @Singleton
  @Provides
  def secureSocialClientIds: SecureSocialClientIds = SecureSocialClientIds("ovlhms1y0fjr", "530357056981814")

  @Singleton
  @Provides
  def actionAuthenticator(myFakeActionAuthenticator: FakeActionAuthenticator): ActionAuthenticator = myFakeActionAuthenticator

  @Singleton
  @Provides
  def fakeActionAuthenticator: FakeActionAuthenticator = new FakeActionAuthenticator
}
