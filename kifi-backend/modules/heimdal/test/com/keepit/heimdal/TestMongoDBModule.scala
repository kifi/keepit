package com.keepit.heimdal


import com.keepit.common.healthcheck.AirbrakeNotifier

import net.codingwell.scalaguice.ScalaModule

import com.google.inject.{Provides, Singleton}


case class TestMongoModule() extends MongoModule {

  def configure() = {}

  @Singleton
  @Provides
  def userEventLoggingRepo(airbrake: AirbrakeNotifier): UserEventLoggingRepo = {
    new TestUserEventLoggingRepo(null, airbrake)
  }


}
