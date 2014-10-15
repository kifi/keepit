package com.keepit.shoebox

import com.google.inject.{ Provides, Singleton }
import com.keepit.common.db.slick._
import com.keepit.commanders.{ KeepsAbuseMonitor, LibraryInvitesAbuseMonitor }
import com.keepit.model.{ LibraryInviteRepo, KeepRepo }
import net.codingwell.scalaguice.ScalaModule
import com.keepit.common.healthcheck.AirbrakeNotifier

case class AbuseControlModule() extends ScalaModule {

  def configure(): Unit = {
  }

  @Provides
  @Singleton
  def keepsAbuseMonitor(keepRepo: KeepRepo, db: Database, airbrake: AirbrakeNotifier): KeepsAbuseMonitor =
    new KeepsAbuseMonitor(
      absoluteWarn = 5000,
      absoluteError = 40000,
      keepRepo = keepRepo,
      db = db,
      airbrake = airbrake)

  @Provides
  @Singleton
  def libraryInvitesAbuseMonitor(libraryInviteRepo: LibraryInviteRepo, db: Database, airbrake: AirbrakeNotifier): LibraryInvitesAbuseMonitor =
    new LibraryInvitesAbuseMonitor(
      absoluteWarn = 20,
      absoluteError = 50,
      libraryInviteRepo = libraryInviteRepo,
      db = db,
      airbrake = airbrake
    )

}

