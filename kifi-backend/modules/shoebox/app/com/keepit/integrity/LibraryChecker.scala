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
    keepRepo: KeepRepo,
    libraryCommander: LibraryCommander,
    airbrake: AirbrakeNotifier) extends Logging {

  private[this] val lock = new AnyRef

  def check(): Unit = lock.synchronized {
    checkSystemLibraries()
    checkLibraryKeeps()
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

  private[integrity] def checkLibraryKeeps(): Unit = {
    log.info("start processing library's last kept date. A library's last_kept field should match the last kept keep")
    val currentTime = DateTime.now()
    val hourInd = currentTime.hourOfDay().get
    val minuteInd = currentTime.minuteOfHour().get / 10
    val index = hourInd * 6 + minuteInd // hour * 6 + minute / 10 (int div)

    val (libraryMap, latestKeptAtMap, numKeepsByLibraryMap) = db.readOnlyMaster { implicit s =>
      val pageSize = libraryRepo.countWithState(LibraryStates.ACTIVE) / 144 // 144 = 24 (hours per day) * 6 (10 minute intervals per hour)
      val libraries = libraryRepo.page(index, pageSize, Set(LibraryStates.INACTIVE))
      val libraryMap = libraries.map(lib => lib.id.get -> lib).toMap
      val allLibraryIds = libraryMap.keySet
      val latestKeptAtMap = keepRepo.latestKeptAtByLibraryIds(allLibraryIds)
      val numKeepsByLibraryMap = keepRepo.getCountsByLibrary(allLibraryIds)
      (libraryMap, latestKeptAtMap, numKeepsByLibraryMap)
    }

    libraryMap.map {
      case (libId, lib) =>
        // check last_kept
        val currentKeptAt = latestKeptAtMap.get(libId).flatten
        lib.lastKept match {
          case None =>
            currentKeptAt match {
              case Some(keptAt) =>
                //airbrake.notify(s"Library ${libId} has no last_kept but has active keeps... update library's last_kept!")
                db.readWrite { implicit s =>
                  libraryRepo.save(lib.copy(lastKept = Some(keptAt)))
                }
              case _ =>
            }
          case Some(lastKeptDate) =>
            currentKeptAt match {
              case Some(keptAt) if keptAt != lastKeptDate =>
                //airbrake.notify(s"Library ${libId} has inconsistent last_kept state. Library is last kept at $lastKeptDate but keep is ${keptAt}... update library's last_kept")
                db.readWrite { implicit s =>
                  libraryRepo.save(lib.copy(lastKept = Some(keptAt)))
                }
              case _ =>
            }
        }

        // check keep count
        numKeepsByLibraryMap.get(libId).map { numKeeps =>
          if (lib.keepCount != numKeeps) {
            //airbrake.notify(s"Library ${libId} has inconsistent keep count. Library's keep count is ${lib.keepCount} but there are ${numKeeps} active keeps... update library's keep_count")
            db.readWrite { implicit s =>
              libraryRepo.save(lib.copy(keepCount = numKeeps))
            }
          }
        }

    }
  }

}
