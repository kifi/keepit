package com.keepit.scraper

import akka.actor.Scheduler
import com.google.inject.{ Provides, Singleton }
import com.keepit.common.healthcheck.AirbrakeNotifier

case class TestScraperServiceClientModule() extends ScraperServiceClientModule {

  def configure() {}

  @Singleton
  @Provides
  def ScraperServiceClient(airbrakeNotifier: AirbrakeNotifier, scheduler: Scheduler): ScraperServiceClient = {
    new FakeScraperServiceClientImpl(airbrakeNotifier, scheduler)
  }

}
