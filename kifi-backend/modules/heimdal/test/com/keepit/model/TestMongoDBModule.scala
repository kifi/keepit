package com.keepit.model

import com.google.inject.{ Provides, Singleton }
import com.keepit.heimdal._

case class FakeMongoModule() extends MongoModule {

  def configure() = {}

  @Provides @Singleton
  def userEventLoggingRepo: UserEventLoggingRepo = new FakeUserEventLoggingRepo

  @Provides @Singleton
  def systemEventLoggingRepo: SystemEventLoggingRepo = new FakeSystemEventLoggingRepo

  @Provides @Singleton
  def anonymousEventLoggingRepo: AnonymousEventLoggingRepo = new FakeAnonymousEventLoggingRepo

  @Provides @Singleton
  def visitorEventLoggingRepo: VisitorEventLoggingRepo = new FakeVisitorEventLoggingRepo

  @Provides @Singleton
  def nonUserEventLoggingRepo: NonUserEventLoggingRepo = new FakeNonUserEventLoggingRepo
}
