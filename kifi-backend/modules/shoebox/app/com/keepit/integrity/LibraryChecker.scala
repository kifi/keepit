package com.keepit.integrity

import com.google.inject.Inject
import com.keepit.commanders.{ LibraryCommander }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.model._
import org.joda.time.DateTime

class LibraryChecker @Inject() (
    db: Database,
    userRepo: UserRepo,
    libraryRepo: LibraryRepo,
    libraryCommander: LibraryCommander,
    airbrake: AirbrakeNotifier) extends Logging {

  private[this] val lock = new AnyRef

  def check(): Unit = lock.synchronized {
    checkSystemLibraries()
  }

  private[integrity] def checkSystemLibraries(): Unit = {
    log.info("start processing user's system generated libraries. One Main & one Secret Library per user")
    val currentTime = DateTime.now()
    val hourInd = currentTime.hourOfDay().get
    val minuteInd = currentTime.minuteOfHour().get / 10
    val index = hourInd * 6 + minuteInd // hour * 6 + minute / 10 (int div)

    db.readOnlyMaster { implicit s =>
      val pageSize = userRepo.countIncluding(UserStates.ACTIVE) / 144 // 144 = 24 (hours per day) * 6 (10 minute intervals per hour)
      userRepo.pageIncluding(UserStates.ACTIVE)(index, pageSize)
    }.map { u =>
      // libraryCommander.internSystemGeneratedLibraries does the following...
      // takes the earliest MAIN & SECRET Library created for a user
      // makes sure user has active ownership of Library (if not, airbrake)
      // inactivates MAIN/SECRET Libraries created later (airbrakes if multiple MAIN/SECRET libraries for a user)
      // if MAIN/SECRET library not created - airbrake & create!
      libraryCommander.internSystemGeneratedLibraries(u.id.get, false)
    }
  }

}
