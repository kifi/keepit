package com.keepit.commanders

import com.google.inject.Inject
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.model.LibraryAccess.OWNER
import com.keepit.model.{ Keep, KeepRepo, LibraryMembership, LibraryMembershipRepo, NormalizedURIRepo, User }
import org.joda.time.DateTime

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

class NewKeepsInLibraryCommander @Inject() (
    db: Database,
    normalizedUriRepo: NormalizedURIRepo,
    libraryMembershipRepo: LibraryMembershipRepo,
    keepRepo: KeepRepo) extends Logging {

  private def lastDateToGetKeepsFrom(libraryMembership: LibraryMembership): DateTime = {
    (libraryMembership.lastViewed, libraryMembership.lastEmailSent) match {
      case (None, None) => libraryMembership.createdAt
      case (Some(date), None) => date
      case (None, Some(date)) => date
      case (Some(date1), Some(date2)) => if (date1.isAfter(date2)) date1 else date2
    }
  }

  private def getOldestLeastViewdKeeps(userId: Id[User], max: Int): Seq[mutable.Stack[Keep]] = {
    db.readOnlyReplica { implicit session =>
      //get only libraries I'm not an owner of
      val libs = mutable.Stack(libraryMembershipRepo.getWithUserId(userId).filterNot(_.access == OWNER).sortBy(_.lastViewed): _*)
      //yes, ugly double mutable code. need to think of a way to make it more functional
      val libraryKeeps = ArrayBuffer[mutable.Stack[Keep]]()
      while (libraryKeeps.size < max && libs.nonEmpty) {
        val libraryMembership: LibraryMembership = libs.pop()
        val since = lastDateToGetKeepsFrom(libraryMembership)
        val keeps = keepRepo.getKeepsFromLibrarySince(since, libraryMembership.libraryId, max) filterNot pornUrl filterNot {
          keep => keptByUser(keep, userId)
        }
        if (keeps.nonEmpty) libraryKeeps.append(mutable.Stack(keeps: _*))
      }
      libraryKeeps
    }
  }

  private def keptByUser(keep: Keep, userId: Id[User])(implicit session: RSession): Boolean = {
    val kept = keepRepo.getByUriAndUser(keep.uriId, userId).isDefined
    if (kept) log.info(s"keep uriId=${keep.uriId} was kept by userId=$userId, filtering out")
    kept
  }

  private def pornUrl(keep: Keep)(implicit session: RSession): Boolean = {
    val restricted = normalizedUriRepo.get(keep.uriId).restriction.isDefined
    if (restricted) log.info(s"keep uriId=${keep.uriId} is restricted, filtering out")
    restricted
  }

  private def pickOldestKeepFromEachLibrary(libraryKeeps: Seq[mutable.Stack[Keep]], max: Int): Seq[Keep] = {
    var keeps = libraryKeeps.filterNot(_.isEmpty)
    val oldKeepsPerLib = ArrayBuffer[Keep]()
    while (oldKeepsPerLib.size < max && keeps.nonEmpty) {
      keeps = keeps.filterNot(_.isEmpty)
      val layer = keeps.map(_.pop())
      oldKeepsPerLib.append(layer: _*)
    }
    oldKeepsPerLib.sortBy(_.createdAt).take(max)
  }

  def getLastViewdKeeps(userId: Id[User], max: Int): Seq[Keep] = {
    //picking sample size that could be up to max * max * 2 from the set we're actually sorting
    //it a bit inefficient but in practice it will be a small number.
    val libraryKeeps = getOldestLeastViewdKeeps(userId, max * 2)
    pickOldestKeepFromEachLibrary(libraryKeeps, max)
  }
}
