package com.keepit.integrity

import com.google.inject.Inject
import com.keepit.commanders.{ LibraryCommander }
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.db.{ SequenceNumber, Id }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.{ NamedStatsdTimer, Logging }
import com.keepit.common.time.Clock
import com.keepit.model._
import com.keepit.common.time._
import org.joda.time.DateTime

import scala.annotation.tailrec
import scala.util.{ Failure, Success, Try }
import scala.util.control.NonFatal

class LibraryChecker @Inject() (
    db: Database,
    userRepo: UserRepo,
    libraryRepo: LibraryRepo,
    libraryMembershipRepo: LibraryMembershipRepo,
    keepRepo: KeepRepo,
    libraryCommander: LibraryCommander,
    airbrake: AirbrakeNotifier,
    systemValueRepo: SystemValueRepo,
    clock: Clock) extends Logging {

  private[this] val lock = new AnyRef
  private[this] var keptDateErrors = 0
  private val timeSlicer = new TimeSlicer(clock)

  private val LAST_KEPT_AND_KEEP_COUNT_NAME = Name[SequenceNumber[Keep]]("integrity_plugin_library_sync")
  private val MEMBER_COUNT_NAME = Name[SequenceNumber[LibraryMembership]]("integrity_plugin_library_member_count")
  private val MEMBER_FETCH_SIZE = Count(500)
  private val KEEP_FETCH_SIZE = Count(500)

  def check(): Unit = lock.synchronized {
    checkSystemLibraries()
    checkLibraryKeeps()
    checkLibraryMembers()
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

  @tailrec
  private final def retry[T](retries: Int)(block: => T): Try[T] = {
    Try(block) match {
      case success: Success[T] => success
      case Failure(e) if !NonFatal(e) => throw e
      case _ if retries > 1 => retry(retries - 1)(block)
      case failure => failure
    }
  }

  private def updateLibrary(library: Library) = retry(3) {
    db.readWrite { implicit session =>
      libraryRepo.save(library)
    }
  }

  def max(xs: Seq[Long]): Option[Long] = {
    if (xs.isEmpty) {
      None
    } else {
      Some(xs.reduceLeft((x, y) => if (x > y) x else y))
    }
  }

  def syncLibraryLastKeptAndKeepCount() {
    val timer = new NamedStatsdTimer("LibraryChecker.syncLibraryLastKeptAndKeepCount")
    val (nextSeqNum, librariesNeedingUpdate, newLibraryKeepCount, latestKeptAtMap) = db.readOnlyMaster { implicit session =>
      val lastSeq = getLastSeqNum(LAST_KEPT_AND_KEEP_COUNT_NAME)

      val keeps = keepRepo.getBySequenceNumber(lastSeq, KEEP_FETCH_SIZE.value.toInt)
      val nextSeqNum = max(keeps.map(_.seq.value)).map(SequenceNumber[Keep](_))
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
            case None => updateLibrary(library.copy(lastKept = Some(keepKeptAt)))
            case Some(libLastKept) if keepKeptAt.getMillis - libLastKept.getMillis > 30000 => {
              updateLibrary(library.copy(lastKept = Some(keepKeptAt)))
            }
            case Some(_) => // all good here
          }
          case None => log.warn(s"a library $libraryId that has keeps since last sequence number has no keeps??")
        }
    }

    // sync library keepCount with sum keeps.active
    librariesNeedingUpdate.foreach {
      case (libraryId, library) =>
        newLibraryKeepCount.get(libraryId) match {
          case Some(count) => updateLibrary(library.copy(keepCount = count))
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
      val members = libraryMembershipRepo.getBySequenceNumber(lastSeq, KEEP_FETCH_SIZE.value.toInt)
      val nextSeqNum = max(members.map(_.seq.value)).map(SequenceNumber[LibraryMembership](_))
      val libraryIds = members.map(_.libraryId).toSet

      val libraries = libraryRepo.getLibraries(libraryIds)
      val libraryMemberCounts = libraryMembershipRepo.countByLibraryId(libraryIds)
      (nextSeqNum, libraries, libraryMemberCounts)
    }

    libraries.foreach {
      case (libraryId, library) =>
        libraryMemberCounts.get(libraryId) match {
          case None => log.warn(s"a library $libraryId that has members since last sequence number has no members??")
          case Some(count) if count != library.memberCount => updateLibrary(library.copy(memberCount = count))
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

  private[integrity] def checkLibraryKeeps(): Unit = {
    log.info("start processing library's last kept date. A library's last_kept field should match the last kept keep")
    val timer = new NamedStatsdTimer("LibraryChecker.checkLibraryKeeps")
    val (index, numIntervals) = getIndex()

    val (libraryMap, latestKeptAtMap, numKeepsByLibraryMap) = db.readOnlyReplica { implicit s =>
      val pageSize = libraryRepo.countWithState(LibraryStates.ACTIVE) / numIntervals
      val libraries = libraryRepo.page(index, pageSize, Set(LibraryStates.INACTIVE))
      val libraryMap = libraries.map(lib => lib.id.get -> lib).toMap
      val allLibraryIds = libraryMap.keySet
      val latestKeptAtMap = keepRepo.latestKeptAtByLibraryIds(allLibraryIds)
      val numKeepsByLibraryMap = allLibraryIds.grouped(100).map { keepRepo.getCountsByLibrary(_) }.foldLeft(Map.empty[Id[Library], Int]) { case (m1, m2) => m1 ++ m2 } // grouped to be more friendly with cache bulkget
      (libraryMap, latestKeptAtMap, numKeepsByLibraryMap)
    }

    libraryMap.map {
      case (libId, lib) =>
        // check last_kept
        val timeFromKeepRepo = latestKeptAtMap.get(libId).flatten
        val timeFromLibrary = lib.lastKept

        (timeFromLibrary, timeFromKeepRepo) match {
          case (None, None) =>
          case (Some(t1), Some(t2)) =>
            val secondsDiff = math.abs(t1.getMillis - t2.getMillis) * 1.0 / 1000
            if (secondsDiff > 30) {
              keptDateErrors += 1
              if (keptDateErrors == 1 || keptDateErrors % 50 == 0) {
                log.warn(s"Library ${libId} has inconsistent last_kept state. Library is last kept at $t1 but keep is ${t2}... update library's last_kept. Total Errors so far: $keptDateErrors")
              }
              db.readWrite { implicit s => libraryRepo.save(lib.copy(lastKept = Some(t2))) }
            }
          case (Some(t1), None) =>
          case (None, Some(t2)) =>
            keptDateErrors += 1
            if (keptDateErrors == 1 || keptDateErrors % 50 == 0) {
              log.warn(s"Library ${libId} has no last_kept but has active keeps... update library's last_kept! Total Errors so far: $keptDateErrors")
            }
            db.readWrite { implicit s => libraryRepo.save(lib.copy(lastKept = Some(t2))) }
        }

        // check keep count
        numKeepsByLibraryMap.get(libId).map { numKeeps =>
          if (lib.keepCount != numKeeps) {
            log.warn(s"Library ${libId} has inconsistent keep count. Library's keep count is ${lib.keepCount} but there are ${numKeeps} active keeps... update library's keep_count")
            db.readWrite { implicit s =>
              libraryRepo.save(lib.copy(keepCount = numKeeps))
            }
          }
        }

    }
    timer.stopAndReport(appLog = true)
  }

  private[integrity] def checkLibraryMembers(): Unit = {
    log.info("start processing library's last kept date. A library's last_kept field should match the last kept keep")
    val timer = new NamedStatsdTimer("LibraryChecker.checkLibraryMembers")
    val (index, numIntervals) = getIndex()

    val (libraryMap, numMembersMap) = db.readOnlyReplica { implicit s =>
      val pageSize = libraryRepo.countWithState(LibraryStates.ACTIVE) / numIntervals
      val libraries = libraryRepo.page(index, pageSize, Set(LibraryStates.INACTIVE))
      val libraryMap = libraries.map(lib => lib.id.get -> lib).toMap
      val allLibraryIds = libraryMap.keySet
      val numMembersMap = libraryMembershipRepo.countByLibraryId(allLibraryIds)
      (libraryMap, numMembersMap)
    }

    libraryMap.map {
      case (libId, lib) =>
        numMembersMap.get(libId).map { numMembers =>
          if (lib.memberCount != numMembers) {
            log.warn(s"Library ${libId} has inconsistent member count. Library's member count is ${lib.memberCount} but there are ${numMembers} active memberships... update library's member_count")
            db.readWrite { implicit s =>
              libraryRepo.save(lib.copy(memberCount = numMembers))
            }
          }
        }
    }
    timer.stopAndReport(appLog = true)
  }

  private def getIndex(): (Int, Int) = {
    timeSlicer.getSliceAndSize(TimeToSliceInDays.ONE_WEEK, OneSliceInMinutes(DataIntegrityPlugin.EVERY_N_MINUTE))
  }

}
