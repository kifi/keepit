package com.keepit.social

import com.google.inject.{ Singleton, Provides }
import com.keepit.slack.SlackCommander
import com.keepit.slack.models.SlackTeamMembershipRepo
import com.keepit.social.providers.PasswordAuthentication
import securesocial.controllers.TemplatesPlugin
import com.keepit.common.social.{ ShoeboxTemplatesPlugin }
import com.keepit.common.db.slick.Database
import com.keepit.model._
import com.keepit.common.healthcheck.{ AirbrakeNotifier }
import com.keepit.common.store.S3ImageStore
import com.keepit.common.time.Clock
import com.keepit.commanders.{ UserCreationCommander, UserEmailAddressCommander, UserCommander, LocalUserExperimentCommander }
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
    userSessionRepo: UserSessionRepo,
    userIdentityHelper: UserIdentityHelper,
    airbrake: AirbrakeNotifier,
    app: play.api.Application): SecureSocialAuthenticatorPlugin = new SecureSocialAuthenticatorPluginImpl(db, userSessionRepo, userIdentityHelper, airbrake, app)

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
    slackMembershipRepo: SlackTeamMembershipRepo,
    socialGraphPlugin: SocialGraphPlugin,
    userCreationCommander: UserCreationCommander,
    userExperimentCommander: LocalUserExperimentCommander,
    userEmailAddressCommander: UserEmailAddressCommander,
    slackCommander: SlackCommander,
    userIdentityHelper: UserIdentityHelper,
    clock: Clock): SecureSocialUserPlugin = new SecureSocialUserPluginImpl(
    db, socialUserInfoRepo, userRepo, userCredRepo, imageStore, airbrake, emailRepo, slackMembershipRepo, socialGraphPlugin, userCreationCommander, userExperimentCommander, userEmailAddressCommander, slackCommander, userIdentityHelper, clock
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
