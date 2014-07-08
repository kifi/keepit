package com.keepit.heimdal

import com.google.inject.{Provides, Singleton}
import com.keepit.model.{AnonymousEventLoggingRepo, UserEventLoggingRepo, SystemEventLoggingRepo, NonUserEventLoggingRepo}


case class TestMongoModule() extends MongoModule {

  def configure() = {}

  @Provides @Singleton
  def userEventLoggingRepo: UserEventLoggingRepo = new TestUserEventLoggingRepo

  @Provides @Singleton
  def systemEventLoggingRepo: SystemEventLoggingRepo = new TestSystemEventLoggingRepo

  @Provides @Singleton
  def anonymousEventLoggingRepo: AnonymousEventLoggingRepo = new TestAnonymousEventLoggingRepo

  @Provides @Singleton
  def nonUserEventLoggingRepo: NonUserEventLoggingRepo = new TestNonUserEventLoggingRepo
}
