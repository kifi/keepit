package com.keepit.social

import com.google.inject.{Singleton, Provides}
import securesocial.controllers.TemplatesPlugin
import com.keepit.common.social.{SocialGraphPlugin, ShoeboxTemplatesPlugin}
import com.keepit.common.db.slick.Database
import com.keepit.model._
import com.keepit.common.healthcheck.HealthcheckPlugin
import com.keepit.common.store.S3ImageStore
import com.keepit.common.controller.{ShoeboxActionAuthenticator, ActionAuthenticator}

case class ShoeboxSecureSocialModule() extends SecureSocialModule {

  def configure {
    bind[ActionAuthenticator].to[ShoeboxActionAuthenticator]
  }

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
    healthPlugin: HealthcheckPlugin,
    app: play.api.Application
  ): SecureSocialAuthenticatorPlugin = new SecureSocialAuthenticatorPluginImpl(db, suiRepo, usRepo, healthPlugin, app)

  @Singleton
  @Provides
  def secureSocialUserPlugin(
    db: Database,
    socialUserInfoRepo: SocialUserInfoRepo,
    userRepo: UserRepo,
    imageStore: S3ImageStore,
    healthcheckPlugin: HealthcheckPlugin,
    userExperimentRepo: UserExperimentRepo,
    emailRepo: EmailAddressRepo,
    socialGraphPlugin: SocialGraphPlugin
  ): SecureSocialUserPlugin = new SecureSocialUserPluginImpl(
    db, socialUserInfoRepo, userRepo, imageStore, healthcheckPlugin, userExperimentRepo, emailRepo, socialGraphPlugin
  )

}
