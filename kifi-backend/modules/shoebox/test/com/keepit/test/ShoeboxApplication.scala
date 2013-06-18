package com.keepit.test

import java.io.File

import com.google.inject.{Provides, Singleton}
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.HealthcheckPlugin
import com.keepit.common.social.SocialGraphPlugin
import com.keepit.common.store.S3ImageStore
import com.keepit.dev.ShoeboxDevGlobal
import com.keepit.model._
import com.keepit.social.{ShoeboxSecureSocialUserPlugin, ShoeboxSecureSocialAuthenticatorPlugin, SecureSocialUserPlugin, SecureSocialAuthenticatorPlugin}

class ShoeboxApplication() extends TestApplication(new TestGlobal(ShoeboxDevGlobal.modules: _*), path = new File("./modules/shoebox/")) {

  @Singleton
  @Provides
  def secureSocialAuthenticatorPlugin(
    db: Database,
    suiRepo: SocialUserInfoRepo,
    usRepo: UserSessionRepo,
    healthPlugin: HealthcheckPlugin,
    app: play.api.Application): SecureSocialAuthenticatorPlugin = {
    new ShoeboxSecureSocialAuthenticatorPlugin(db, suiRepo, usRepo, healthPlugin, app)
  }

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
    socialGraphPlugin: SocialGraphPlugin): SecureSocialUserPlugin = {
    new ShoeboxSecureSocialUserPlugin(
      db, socialUserInfoRepo, userRepo, imageStore, healthcheckPlugin, userExperimentRepo, emailRepo, socialGraphPlugin)
  }
}

