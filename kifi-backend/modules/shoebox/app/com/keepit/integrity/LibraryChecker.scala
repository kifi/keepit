package com.keepit.integrity

import com.google.inject.Inject
import com.keepit.commanders.LibraryCommander
import com.keepit.common.core.tailrec
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.db.slick.Database
import com.keepit.common.db.{ Id, SequenceNumber }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.{ Logging, NamedStatsdTimer }
import com.keepit.common.time.{ Clock, _ }
import com.keepit.model._

class LibraryChecker @Inject() (val airbrake: AirbrakeNotifier,
    val clock: Clock,
    val db: Database,
    val keepRepo: KeepRepo,
    val libraryCommander: LibraryCommander,
    val libraryMembershipRepo: LibraryMembershipRepo,
    val libraryRepo: LibraryRepo,
    val organizationRepo: OrganizationRepo,
    val userRepo: UserRepo,
    val systemValueRepo: SystemValueRepo) extends Logging {

  private[this] val lock = new AnyRef
  private val timeSlicer = new TimeSlicer(clock)

  private[integrity] val LAST_KEPT_AND_KEEP_COUNT_NAME = Name[SequenceNumber[Keep]]("integrity_plugin_library_sync")
  private[integrity] val MEMBER_COUNT_NAME = Name[SequenceNumber[LibraryMembership]]("integrity_plugin_library_member_count")
  private[integrity] val LIBRARY_MOVED_NAME = Name[SequenceNumber[Library]]("integrity_plugin_library_moved")
  private val MEMBER_FETCH_SIZE = 500
  private val KEEP_FETCH_SIZE = 500
  private val LIBRARY_FETCH_SIZE = 250

  def check(): Unit = lock.synchronized {
    syncLibraryLastKeptAndKeepCount() // reads keeps, updates libraries
    syncLibraryMemberCounts() // reads libraryMembership, updates libraries
    syncOnLibraryMove() // reads libraries, updates keeps. (This is kind of recursive, but not really as I only update the ones that are wrong.)
  }

  // terminates early when it runs out of elements.
  @tailrec
  final def stream[A](f: (Limit, Offset) => Iterable[A], totalItems: Limit, pageSize: Limit, currentOffset: Offset = Offset(0))(block: A => Unit): Unit = {
    val resultsLeftToGet = totalItems.value match {
      case -1 => pageSize.value // until the function runs out of elements.
      case _ => Math.max(totalItems.value - currentOffset.value, 0)
    }

    resultsLeftToGet match {
      case 0 =>
      case results if (results < pageSize.value) => f(Limit(resultsLeftToGet), currentOffset).foreach(block)
      case _ =>
        val items = f(Limit(pageSize.value), currentOffset)
        items.foreach(block)
        if (items.size == pageSize.value) {
          stream(f, totalItems, pageSize, Offset(currentOffset.value + items.size))(block)
        }
    }
  }

  private[integrity] def syncOnLibraryMove(): Unit = {
    val timer = new NamedStatsdTimer("LibraryChecker.syncOnLibraryMove")
    val libraries = db.readOnlyReplica { implicit s =>
      val lastSeq = getLastSeqNum(LIBRARY_MOVED_NAME)
      val libraries = libraryRepo.getBySequenceNumber(lastSeq, LIBRARY_FETCH_SIZE)
      libraries
    }
    val nextSeqNum = if (libraries.isEmpty) None else Some(libraries.map(_.seq).max)

    libraries.foreach { library =>
      // only gets the keeps that have no matching orgId
      def getKeeps(limit: Limit, offset: Offset): Iterable[Keep] = db.readOnlyReplica { implicit s =>
        keepRepo.getByLibraryWithoutOrgId(library.id.get, library.organizationId, offset = offset, limit = limit)
      }
      // iterate over all keeps by 100keep pages.
      db.readWrite { implicit session =>
        stream(getKeeps, totalItems = Limit(-1), pageSize = Limit(100)) { keep =>
          updateKeep(keep.id.get, _.copy(organizationId = library.organizationId))
        }
      }
    }

    nextSeqNum.foreach { seqNum =>
      db.readWrite { implicit session =>
        systemValueRepo.setSequenceNumber(LIBRARY_MOVED_NAME, seqNum)
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

  private def updateKeep(id: Id[Keep], mutator: Keep => Keep)(implicit session: RWSession) = {
    // same as below
    keepRepo.save(mutator(keepRepo.get(id)))
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
      val nextSeqNum = if (keeps.isEmpty) None else Some(keeps.map(_.seq).max)
      val libraryIds = keeps.map(_.libraryId.get).toSet

      val newLibraryKeepCount = keepRepo.getCountsByLibrary(libraryIds)
      val latestKeptAtMap = keepRepo.latestKeptAtByLibraryIds(libraryIds)
      val librariesNeedingUpdate = libraryRepo.getLibraries(libraryIds)
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

      val libraries = libraryRepo.getLibraries(libraryIds)
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
}
