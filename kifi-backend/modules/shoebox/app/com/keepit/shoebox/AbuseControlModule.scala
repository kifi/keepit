package com.keepit.shoebox

import com.google.inject.{Provides, Singleton}
import com.keepit.common.db.slick._
import com.keepit.commanders.KeepsAbuseMonitor
import com.keepit.model.BookmarkRepo
import net.codingwell.scalaguice.ScalaModule
import com.keepit.common.healthcheck.AirbrakeNotifier

case class AbuseControlModule() extends ScalaModule {

  def configure(): Unit = {
  }

  @Provides
  @Singleton
  def keepsAbuseMonitor(bookmarkRepo: BookmarkRepo, db: Database, airbrake: AirbrakeNotifier): KeepsAbuseMonitor =
    new KeepsAbuseMonitor(
      absoluteWarn = 5000,
      absoluteError = 20000,
      bookmarkRepo = bookmarkRepo,
      db = db,
      airbrake = airbrake)

}


