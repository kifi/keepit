package com.keepit.integrity

import com.google.inject.Inject
import com.keepit.commanders.{ KeepCommander, KeepToLibraryCommander, LibraryInfoCommander, LibraryCommander }
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.db.slick.Database
import com.keepit.common.db.{ Id, SequenceNumber }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.{ Logging, NamedStatsdTimer }
import com.keepit.common.time.{ Clock, _ }
import com.keepit.model._
import com.keepit.common.core._

class LibraryChecker @Inject() (val airbrake: AirbrakeNotifier,
    val clock: Clock,
    val db: Database,
    val keepRepo: KeepRepo,
    val keepCommander: KeepCommander,
    val ktlRepo: KeepToLibraryRepo,
    val ktlCommander: KeepToLibraryCommander,
    libraryCommander: LibraryCommander,
    val libraryMembershipRepo: LibraryMembershipRepo,
    val libraryRepo: LibraryRepo,
    val userRepo: UserRepo,
    val systemValueRepo: SystemValueRepo) extends Logging {

  private[this] val lock = new AnyRef
  private val timeSlicer = new TimeSlicer(clock)

  private[integrity] val LAST_KEPT_AND_KEEP_COUNT_NAME = Name[SequenceNumber[Keep]]("integrity_plugin_library_sync")
  private[integrity] val MEMBER_COUNT_NAME = Name[SequenceNumber[LibraryMembership]]("integrity_plugin_library_member_count")
  private[integrity] val LIBRARY_MOVED_NAME = Name[SequenceNumber[Library]]("integrity_plugin_library_moved")
  private[integrity] val LIBRARY_DELETED_NAME = Name[SequenceNumber[Library]]("integrity_plugin_library_deleted")
  private val MEMBER_FETCH_SIZE = 500
  private val KEEP_FETCH_SIZE = 500
  private val LIBRARY_FETCH_SIZE = 250

  def check(): Unit = lock.synchronized {
    syncLibraryLastKeptAndKeepCount() // reads keeps, updates libraries
    syncLibraryMemberCounts() // reads libraryMembership, updates libraries
    syncOnLibraryMove() // reads libraries, updates keeps. (This is kind of recursive, but not really as I only update the ones that are wrong.)
    syncOnLibraryDelete()
  }

  private[integrity] def syncOnLibraryDelete(): Unit = {
    val libraries = db.readOnlyReplica { implicit s =>
      val lastSeq = getLastSeqNum(LIBRARY_DELETED_NAME)
      libraryRepo.getBySequenceNumber(lastSeq, LIBRARY_FETCH_SIZE)
    }

    libraries.map(_.seq).maxOpt.foreach { maxSeq =>
      libraries.foreach { library =>
        if (!library.isActive) {
          db.readWrite { implicit session =>
            val zombieKtls = ktlRepo.getAllByLibraryId(library.id.get)
            if (zombieKtls.nonEmpty) {
              //airbrake.notify(s"Library ${library.id.get} has zombie ktls: ${zombieKtls.map(_.id.get)}")
              log.error(s"Library ${library.id.get} has zombie ktls: ${zombieKtls.map(_.id.get)}")

              // TODO(ryan): when it's acceptable for a keep to be removed from a library, kill the code below and replace it with this commented out line
              // zombieKtls.foreach(ktlCommander.deactivate)
              zombieKtls.foreach { ktl =>
                val keep = keepRepo.get(ktl.keepId)
                keepCommander.deactivateKeep(keep)
              }
            }
          }
        } else {
          db.readWrite { implicit session =>
            val ownerMembershipIsActive = libraryMembershipRepo.getWithLibraryIdAndUserId(library.id.get, library.ownerId).exists(_.isActive)
            if (!ownerMembershipIsActive) {
              airbrake.notify(s"[zombie-libs] found a library id=${library.id.get} where owner id=${library.ownerId} does not have an active membership, deactivating it")
              libraryRepo.deactivate(library)
            }
          }
        }
      }

      db.readWrite { implicit session =>
        systemValueRepo.setSequenceNumber(LIBRARY_DELETED_NAME, maxSeq)
      }
    }
  }

  private[integrity] def syncOnLibraryMove(): Unit = {
    val timer = new NamedStatsdTimer("LibraryChecker.syncOnLibraryMove")
    val libraries = db.readOnlyReplica { implicit s =>
      val lastSeq = getLastSeqNum(LIBRARY_MOVED_NAME)
      libraryRepo.getBySequenceNumber(lastSeq, LIBRARY_FETCH_SIZE)
    }

    libraries.map(_.seq).maxOpt.foreach { maxSeq =>
      libraries.foreach { library =>
        var fixedInPreviousBatch = 0
        do {
          db.readWrite { implicit session =>
            val invalidKtls = ktlRepo.getByLibraryWithInconsistentOrgId(library.id.get, library.organizationId, limit = Limit(100))
            invalidKtls.foreach { ktl =>
              ktlRepo.save(ktl.withOrganizationId(library.organizationId))
            }
            fixedInPreviousBatch = invalidKtls.size
          }
        } while (fixedInPreviousBatch > 0)
      }

      db.readWrite { implicit session =>
        systemValueRepo.setSequenceNumber(LIBRARY_MOVED_NAME, maxSeq)
      }
    }
    timer.stopAndReport(appLog = true)
  }

  private[integrity] def checkSystemLibraries(): Unit = {
    log.info("start processing user's system generated libraries. One Main & one Secret Library per user")
    val timer = new NamedStatsdTimer("LibraryChecker.checkSystemLibraries")
    val (index, numIntervals) = getIndex()

    db.readOnlyReplica { implicit s =>
      val pageSize = userRepo.countIncluding(UserStates.ACTIVE) / numIntervals
      userRepo.pageIncluding(UserStates.ACTIVE)(index, pageSize)
    }.map { u =>
      // libraryInfoCommander.internSystemGeneratedLibraries does the following...
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

  private def updateKeep(id: Id[Keep], mutator: Keep => Keep)(implicit session: RWSession) = {
    // same as below
    keepRepo.save(mutator(keepRepo.getNoCache(id)))
  }

  private def updateLibrary(id: Id[Library], mutator: Library => Library) = db.readWrite { implicit session =>
    /* Since we can be processing hundreds of libraries, the library can be out of date by the time we get to actually updating it with our plugin.
    Do not overwrite new data with old; Instead just refetch the library and update it. */
    libraryRepo.save(mutator(libraryRepo.getNoCache(id)))
  }

  def syncLibraryLastKeptAndKeepCount() {
    val timer = new NamedStatsdTimer("LibraryChecker.syncLibraryLastKeptAndKeepCount")
    val (nextSeqNum, librariesNeedingUpdate, newLibraryKeepCount, latestKeptAtMap) = db.readOnlyMaster { implicit session =>
      val lastSeq = getLastSeqNum(LAST_KEPT_AND_KEEP_COUNT_NAME)

      val keeps = keepRepo.getBySequenceNumber(lastSeq, KEEP_FETCH_SIZE)
      val nextSeqNum = if (keeps.isEmpty) None else Some(keeps.map(_.seq).max)
      val libraryIds = ktlRepo.getAllByKeepIds(keeps.map(_.id.get).toSet).values.flatMap(_.map(_.libraryId)).toSet

      val newLibraryKeepCount = ktlRepo.getCountsByLibraryIds(libraryIds)
      val latestKeptAtMap = ktlRepo.latestKeptAtByLibraryIds(libraryIds)
      val librariesNeedingUpdate = libraryRepo.getActiveByIds(libraryIds)
      (nextSeqNum, librariesNeedingUpdate, newLibraryKeepCount, latestKeptAtMap)
    }
    // sync library lastKept with most recent keep.lastKept
    def updateLibraryLastKeptAt(libraryId: Id[Library], library: Library) {
      latestKeptAtMap(libraryId).foreach { keepKeptAt =>
        library.lastKept match {
          case None =>
            updateLibrary(libraryId, _.copy(lastKept = Some(keepKeptAt)))
          case Some(libLastKept) if math.abs(keepKeptAt.getMillis - libLastKept.getMillis) > 30000 => {
            updateLibrary(libraryId, _.copy(lastKept = Some(keepKeptAt)))
          }
          case Some(_) => // all good here
        }
      }
    }

    def updateLibraryKeepCount(libraryId: Id[Library], library: Library) {
      newLibraryKeepCount.get(libraryId) match {
        case Some(count) if library.keepCount != count => updateLibrary(libraryId, _.copy(keepCount = count))
        case _ => // all good here.
      }
    }

    librariesNeedingUpdate.foreach {
      case (libraryId, library) =>
        updateLibraryLastKeptAt(libraryId, library)
        updateLibraryKeepCount(libraryId, library)
    }

    nextSeqNum.foreach(seqNum => db.readWrite { implicit session =>
      systemValueRepo.setSequenceNumber(LAST_KEPT_AND_KEEP_COUNT_NAME, seqNum)
    })
    timer.stopAndReport(appLog = true)
  }

  def syncLibraryMemberCounts() {
    val timer = new NamedStatsdTimer("LibraryChecker.syncLibraryMemberCounts")
    val (nextSeqNum, libraries, libraryMemberCounts) = db.readOnlyMaster { implicit session =>
      val lastSeq = getLastSeqNum(MEMBER_COUNT_NAME)
      val members = libraryMembershipRepo.getBySequenceNumber(lastSeq, MEMBER_FETCH_SIZE)
      val nextSeqNum = if (members.isEmpty) None else Some(members.map(_.seq).max)
      val libraryIds = members.map(_.libraryId).toSet

      val libraries = libraryRepo.getActiveByIds(libraryIds)
      val libraryMemberCounts = libraryMembershipRepo.countByLibraryId(libraryIds)
      (nextSeqNum, libraries, libraryMemberCounts)
    }

    libraries.foreach {
      case (libraryId, library) =>
        libraryMemberCounts.get(libraryId) match {
          case None => log.warn(s"a library $libraryId that has members since last sequence number has no members??")
          case Some(count) if count != library.memberCount => updateLibrary(libraryId, _.copy(memberCount = count))
          case Some(_) => // here be dragons counting your libraries correctly
        }
    }
    nextSeqNum.foreach(seqNum => db.readWrite { implicit session =>
      systemValueRepo.setSequenceNumber(MEMBER_COUNT_NAME, seqNum)
    })

    timer.stopAndReport(appLog = true)
  }

  private def getIndex(): (Int, Int) = {
    timeSlicer.getSliceAndSize(TimeToSliceInDays.ONE_WEEK, OneSliceInMinutes(DataIntegrityPlugin.EVERY_N_MINUTE))
  }

  def keepVisibilityCheck(libId: Id[Library]): Int = {
    db.readWrite { implicit s =>
      val lib = libraryRepo.get(libId)
      var total = 0
      var done = false
      val LIMIT = 500
      while (!done) {
        val ktlsToFix = ktlRepo.getByLibraryWithInconsistentVisibility(libId, expectedVisibility = lib.visibility, Limit(LIMIT))
        total += ktlsToFix.length
        ktlsToFix.foreach { ktl => ktlCommander.syncWithLibrary(ktl, lib) }
        if (ktlsToFix.isEmpty) done = true
      }
      total
    }
  }
}
