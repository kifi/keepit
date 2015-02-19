package com.keepit.shoebox

import com.google.inject.{ Provides, Singleton }
import com.keepit.common.db.slick._
import com.keepit.commanders.{ KeepsAbuseMonitor, LibraryInvitesAbuseMonitor }
import com.keepit.model.{ UserExperimentRepo, LibraryInviteRepo, KeepRepo }
import net.codingwell.scalaguice.ScalaModule
import com.keepit.common.healthcheck.AirbrakeNotifier

case class AbuseControlModule() extends ScalaModule {

  def configure(): Unit = {
  }

  @Provides
  @Singleton
  def keepsAbuseMonitor(keepRepo: KeepRepo, db: Database, airbrake: AirbrakeNotifier, experiments: UserExperimentRepo): KeepsAbuseMonitor =
    new KeepsAbuseMonitor(
      absoluteWarn = 10000,
      absoluteError = 70000,
      keepRepo = keepRepo,
      db = db,
      experiments = experiments,
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

