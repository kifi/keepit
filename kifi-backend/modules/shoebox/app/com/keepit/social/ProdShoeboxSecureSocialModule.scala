package com.keepit.social

import com.google.inject.{Singleton, Provides}
import securesocial.controllers.TemplatesPlugin
import com.keepit.common.social.{ShoeboxTemplatesPlugin}
import com.keepit.common.db.slick.Database
import com.keepit.model._
import com.keepit.common.healthcheck.{AirbrakeNotifier, AirbrakeError}
import com.keepit.common.store.S3ImageStore
import com.keepit.common.controller.{ShoeboxActionAuthenticator, ActionAuthenticator}
import com.keepit.heimdal.{HeimdalServiceClient, EventContextBuilderFactory}
import com.keepit.common.time.Clock


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
    emailRepo: EmailAddressRepo,
    socialGraphPlugin: SocialGraphPlugin,
    userEventContextBuilder: EventContextBuilderFactory,
    heimdal: HeimdalServiceClient,
    userExperimentRepo: UserExperimentRepo,
    clock: Clock
  ): SecureSocialUserPlugin = new SecureSocialUserPluginImpl(
    db, socialUserInfoRepo, userRepo, userCredRepo, imageStore, airbrake, emailRepo, socialGraphPlugin, userEventContextBuilder, heimdal, userExperimentRepo, clock
  )
}

case class ProdShoeboxSecureSocialModule() extends ShoeboxSecureSocialModule {

  def configure {
    bind[ActionAuthenticator].to[ShoeboxActionAuthenticator]
  }
}
