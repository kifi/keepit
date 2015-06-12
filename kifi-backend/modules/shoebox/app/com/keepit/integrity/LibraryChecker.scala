package com.keepit.integrity

import com.google.inject.Inject
import com.keepit.commanders.LibraryCommander
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.common.db.{ Id, SequenceNumber }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.{ Logging, NamedStatsdTimer }
import com.keepit.common.time.{ Clock, _ }
import com.keepit.model._

import scala.annotation.tailrec
import scala.util.control.NonFatal
import scala.util.{ Failure, Success, Try }

class LibraryChecker @Inject() (val airbrake: AirbrakeNotifier,
    val clock: Clock,
    val db: Database,
    val keepRepo: KeepRepo,
    val libraryCommander: LibraryCommander,
    val libraryMembershipRepo: LibraryMembershipRepo,
    val libraryRepo: LibraryRepo,
    val userRepo: UserRepo,
    val systemValueRepo: SystemValueRepo) extends Logging {

  private[this] val lock = new AnyRef
  private val timeSlicer = new TimeSlicer(clock)

  private val LAST_KEPT_AND_KEEP_COUNT_NAME = Name[SequenceNumber[Keep]]("integrity_plugin_library_sync")
  private val MEMBER_COUNT_NAME = Name[SequenceNumber[LibraryMembership]]("integrity_plugin_library_member_count")
  private val MEMBER_FETCH_SIZE = 500
  private val KEEP_FETCH_SIZE = 500

  def check(): Unit = lock.synchronized {
    checkSystemLibraries()
    syncLibraryLastKeptAndKeepCount()
    syncLibraryMemberCounts()
  }

  private[integrity] def checkSystemLibraries(): Unit = {
    log.info("start processing user's system generated libraries. One Main & one Secret Library per user")
    val timer = new NamedStatsdTimer("LibraryChecker.checkSystemLibraries")
    val (index, numIntervals) = getIndex()

    db.readOnlyReplica { implicit s =>
      val pageSize = userRepo.countIncluding(UserStates.ACTIVE) / numIntervals
      userRepo.pageIncluding(UserStates.ACTIVE)(index, pageSize)
    }.map { u =>
      // libraryCommander.internSystemGeneratedLibraries does the following...
      // takes the earliest MAIN & SECRET Library created for a user
      // makes sure user has active ownership of Library (if not, airbrake)
      // inactivates MAIN/SECRET Libraries created later (airbrakes if multiple MAIN/SECRET libraries for a user)
      // if MAIN/SECRET library not created - airbrake & create!
      libraryCommander.internSystemGeneratedLibraries(u.id.get, false)
    }
    timer.stopAndReport(appLog = true)
  }

  private def getLastSeqNum[T](key: Name[SequenceNumber[T]])(implicit session: RSession) = {
    systemValueRepo.getSequenceNumber(key).getOrElse(SequenceNumber[T](0))
  }

  private def updateLibrary(id: Id[Library], mutator: Library => Library) = db.readWrite { implicit session =>
    /* Since we can be processing hundreds of libraries, the library can be out of date by the time we get to actually updating it with our plugin.
    Do not overwrite new data with old; Instead just refetch the library and update it. */
    libraryRepo.save(mutator(libraryRepo.get(id)))
  }

  def syncLibraryLastKeptAndKeepCount() {
    val timer = new NamedStatsdTimer("LibraryChecker.syncLibraryLastKeptAndKeepCount")
    val (nextSeqNum, librariesNeedingUpdate, newLibraryKeepCount, latestKeptAtMap) = db.readOnlyMaster { implicit session =>
      val lastSeq = getLastSeqNum(LAST_KEPT_AND_KEEP_COUNT_NAME)

      val keeps = keepRepo.getBySequenceNumber(lastSeq, KEEP_FETCH_SIZE)
      val nextSeqNum = keeps.length match {
        case length if length > 0 => Some(keeps.map(_.seq).max)
        case _ => None
      }
      val libraryIds = keeps.map(_.libraryId.get).toSet

      val newLibraryKeepCount = keepRepo.getCountsByLibrary(libraryIds)
      val latestKeptAtMap = keepRepo.latestKeptAtByLibraryIds(libraryIds)
      val librariesNeedingUpdate = libraryRepo.getLibraries(libraryIds)
      (nextSeqNum, librariesNeedingUpdate, newLibraryKeepCount, latestKeptAtMap)
    }
    // sync library lastKept with most recent keep.lastKept
    librariesNeedingUpdate.foreach {
      case (libraryId, library) =>
        latestKeptAtMap(libraryId) match {
          case Some(keepKeptAt) => library.lastKept match {
            case None =>
              updateLibrary(libraryId, _.copy(lastKept = Some(keepKeptAt))) match {
                case Success(success) => // success
                case Failure(e) =>
                  log.warn(s"a library $libraryId failed to update its lastKept to $keepKeptAt", e)
              }
            case Some(libLastKept) if keepKeptAt.getMillis - libLastKept.getMillis > 30000 => {
              updateLibrary(libraryId, _.copy(lastKept = Some(keepKeptAt))) match {
                case Success(success) => // success!
                case Failure(e) =>
                  log.warn(s"a library $libraryId failed to update its lastKept to $keepKeptAt", e)
              }
            }
            case Some(_) => // all good here
          }
          case None =>
            log.warn(s"a library $libraryId that has keeps since last sequence number has no keeps??")
        }
    }

    // snapshot of library state from above.
    librariesNeedingUpdate.foreach {
      // sync library keepCount with sum keeps.active
      case (libraryId, library) =>
        newLibraryKeepCount.get(libraryId) match {
          case Some(count) if library.keepCount != count => updateLibrary(libraryId, _.copy(keepCount = count)) match {
            case Success(_) => // all good here
            case Failure(e) => log.warn(s"a library $libraryId failed to update it's keepCount to $count", e)
          }
          case Some(_) => // all good here.
          case None => log.warn(s"a library $libraryId that has keeps since last sequence number has no keeps??")
        }
    }
    nextSeqNum match {
      case Some(seqNum) => db.readWrite { implicit session =>
        systemValueRepo.setSequenceNumber(LAST_KEPT_AND_KEEP_COUNT_NAME, seqNum)
      }
      case None =>
    }
    timer.stopAndReport(appLog = true)
  }

  def syncLibraryMemberCounts() {
    val timer = new NamedStatsdTimer("LibraryChecker.syncLibraryMemberCounts")
    val (nextSeqNum, libraries, libraryMemberCounts) = db.readOnlyMaster { implicit session =>
      val lastSeq = getLastSeqNum(MEMBER_COUNT_NAME)
      val members = libraryMembershipRepo.getBySequenceNumber(lastSeq, MEMBER_FETCH_SIZE)
      val nextSeqNum = members.length match {
        case length if length > 0 => Some(members.map(_.seq).max)
        case _ => None
      }
      val libraryIds = members.map(_.libraryId).toSet

      val libraries = libraryRepo.getLibraries(libraryIds)
      val libraryMemberCounts = libraryMembershipRepo.countByLibraryId(libraryIds)
      (nextSeqNum, libraries, libraryMemberCounts)
    }

    libraries.foreach {
      case (libraryId, library) =>
        libraryMemberCounts.get(libraryId) match {
          case None => log.warn(s"a library $libraryId that has members since last sequence number has no members??")
          case Some(count) if count != library.memberCount => updateLibrary(libraryId, _.copy(memberCount = count)) match {
            case Success(_) => // success
            case Failure(e) => log.warn(s"a library $libraryId failed to update its memberCount to $count", e)
          }
          case Some(_) => // here be dragons counting your libraries correctly
        }
    }
    nextSeqNum match {
      case Some(seqNum) => db.readWrite { implicit session =>
        systemValueRepo.setSequenceNumber(MEMBER_COUNT_NAME, seqNum)
      }
      case None =>
    }

    timer.stopAndReport(appLog = true)
  }

  private def getIndex(): (Int, Int) = {
    timeSlicer.getSliceAndSize(TimeToSliceInDays.ONE_WEEK, OneSliceInMinutes(DataIntegrityPlugin.EVERY_N_MINUTE))
  }
}
