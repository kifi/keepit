package com.keepit.common.controller

import com.keepit.common.healthcheck._
import com.google.inject.{Singleton, Provides}
import net.codingwell.scalaguice.ScalaModule
import com.keepit.common.db.slick._
import com.keepit.model._
import com.keepit.social._
import play.api.Play.current

case class AuthHelperModule() extends ScalaModule {

  def configure() {
  }

  @Provides
  @Singleton
  def authHelper(db: Database, socialUserInfoRepo: SocialUserInfoRepo, userRepo: UserRepo): AuthHelper =
    new AuthHelper(db, socialUserInfoRepo, userRepo)

  @Provides
  @Singleton
  def secureSocialAuthenticatorPlugin(db: Database, socialUserInfoRepo: SocialUserInfoRepo, userSessionRepo: UserSessionRepo, airbrake: AirbrakeNotifier): SecureSocialAuthenticatorPlugin =
    new SecureSocialAuthenticatorPluginImpl(db, socialUserInfoRepo, userSessionRepo, airbrake, current)

}
