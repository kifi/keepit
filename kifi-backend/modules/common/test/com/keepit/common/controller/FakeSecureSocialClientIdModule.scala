package com.keepit.common.controller

import com.google.inject.{ Inject, Singleton, Provides }
import com.keepit.common.controller.FortyTwoCookies.{ KifiInstallationCookie, ImpersonateCookie }
import com.keepit.common.db.{ ExternalId, State, Id }
import com.keepit.common.healthcheck.{ AirbrakeNotifier, AirbrakeError }
import com.keepit.common.logging.Logging
import com.keepit.common.net.URI
import com.keepit.common.service.FortyTwoServices
import com.keepit.model._
import com.keepit.social.{ SecureSocialClientIds, SocialNetworkType, SocialId }

import play.api.mvc._
import securesocial.core._

import net.codingwell.scalaguice.ScalaModule
import scala.concurrent.Future

case class FakeSecureSocialClientIdModule() extends ScalaModule with Logging {
  def configure(): Unit = {}

  @Singleton
  @Provides
  def secureSocialClientIds: SecureSocialClientIds = SecureSocialClientIds("ovlhms1y0fjr", "530357056981814")

}

