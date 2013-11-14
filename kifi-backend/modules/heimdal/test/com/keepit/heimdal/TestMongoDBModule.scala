package com.keepit.heimdal

import com.google.inject.{Provides, Singleton}


case class TestMongoModule() extends MongoModule {

  def configure() = {}

  @Provides @Singleton
  def userEventLoggingRepo: UserEventLoggingRepo = new TestUserEventLoggingRepo

  @Provides @Singleton
  def systemEventLoggingRepo: SystemEventLoggingRepo = new TestSystemEventLoggingRepo
}
