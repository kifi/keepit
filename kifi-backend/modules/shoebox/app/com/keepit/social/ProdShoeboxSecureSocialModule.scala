package com.keepit.social

import com.google.inject.{ Singleton, Provides }
import com.keepit.social.providers.PasswordAuthentication
import securesocial.controllers.TemplatesPlugin
import com.keepit.common.social.{ ShoeboxTemplatesPlugin }
import com.keepit.common.db.slick.Database
import com.keepit.model._
import com.keepit.common.healthcheck.{ AirbrakeNotifier, AirbrakeError }
import com.keepit.common.store.S3ImageStore
import com.keepit.heimdal.{ HeimdalServiceClient, HeimdalContextBuilderFactory }
import com.keepit.common.time.Clock
import com.keepit.commanders.{ UserCommander, LocalUserExperimentCommander }
import play.api.Play.current
import com.keepit.controllers.core.OAuth2CommonConfig

trait ShoeboxSecureSocialModule extends SecureSocialModule {

  @Provides
  @Singleton
  def secureSocialTemplatesPlugin(app: play.api.Application): TemplatesPlugin = {
    new ShoeboxTemplatesPlugin(app)
  }

  @Singleton
  @Provides
  def secureSocialAuthenticatorPlugin(
    db: Database,
    suiRepo: SocialUserInfoRepo,
    usRepo: UserSessionRepo,
    airbrake: AirbrakeNotifier,
    app: play.api.Application): SecureSocialAuthenticatorPlugin = new SecureSocialAuthenticatorPluginImpl(db, suiRepo, usRepo, airbrake, app)

  @Singleton
  @Provides
  def secureSocialUserPlugin(
    db: Database,
    socialUserInfoRepo: SocialUserInfoRepo,
    userRepo: UserRepo,
    userCredRepo: UserCredRepo,
    imageStore: S3ImageStore,
    airbrake: AirbrakeNotifier,
    emailRepo: UserEmailAddressRepo,
    socialGraphPlugin: SocialGraphPlugin,
    userCommander: UserCommander,
    userExperimentCommander: LocalUserExperimentCommander,
    clock: Clock): SecureSocialUserPlugin = new SecureSocialUserPluginImpl(
    db, socialUserInfoRepo, userRepo, userCredRepo, imageStore, airbrake, emailRepo, socialGraphPlugin, userCommander, userExperimentCommander, clock
  )

  @Singleton
  @Provides
  def oauthConfig: OAuth2CommonConfig = OAuth2CommonConfig(current.configuration.getString("oauth2.approval.prompt").getOrElse("force"))
}

case class ProdShoeboxSecureSocialModule() extends ShoeboxSecureSocialModule {

  def configure {
    bind[PasswordAuthentication].to[UserPasswordAuthentication]
  }

  @Singleton
  @Provides
  def secureSocialClientIds: SecureSocialClientIds = {
    val conf = current.configuration.getConfig("securesocial").get
    SecureSocialClientIds(
      conf.getString("linkedin.clientId").get,
      conf.getString("facebook.clientId").get)
  }
}
