package com.keepit.model.tracking

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.controllers.{ VisitorEventHandler, UserEventHandler }
import com.keepit.heimdal._
import com.keepit.model.{ Library, User }
import org.joda.time.DateTime

@Singleton
class LibraryViewTrackingCommander @Inject() (
    db: Database,
    libViewRepo: LibraryViewTrackingRepo) extends UserEventHandler with VisitorEventHandler {

  private def extractOwnerId(context: HeimdalContext): Option[Id[User]] = context.get[String]("ownerId").map { x => Id[User](x.toLong) }
  private def extractLibId(context: HeimdalContext): Option[Id[Library]] = context.get[String]("libraryId").map { x => Id[Library](x.toLong) }

  def handleUserEvent(event: UserEvent): Unit = {
    event match {
      case UserEvent(userId, context, UserEventTypes.VIEWED_LIBRARY, time) =>
        for {
          owner <- extractOwnerId(context)
          libId <- extractLibId(context)
        } yield {
          val viewerId = context.get[String]("viewerId").map { x => Id[User](x.toLong) }
          if (viewerId != Some(owner)) {
            db.readWrite { implicit s => libViewRepo.save(LibraryViewTracking(ownerId = owner, libraryId = libId, viewerId = viewerId, source = LibraryViewSource.fromContext(context))) }
          }
        }

      case _ =>
    }
  }

  def handleVisitorEvent(event: VisitorEvent): Unit = {
    event match {
      case VisitorEvent(context, VisitorEventTypes.VIEWED_LIBRARY, time) =>
        for {
          owner <- extractOwnerId(context)
          libId <- extractLibId(context)
        } yield {
          db.readWrite { implicit s => libViewRepo.save(LibraryViewTracking(ownerId = owner, libraryId = libId, viewerId = None, source = LibraryViewSource.fromContext(context))) }
        }

      case _ =>
    }
  }

  def getTotalViews(ownerId: Id[User], since: DateTime): Int = {
    db.readOnlyReplica { implicit s => libViewRepo.getTotalViews(ownerId, since) }
  }

  def getTopViewedLibrariesAndCounts(ownerId: Id[User], since: DateTime, limit: Int): Map[Id[Library], Int] = {
    db.readOnlyReplica { implicit s => libViewRepo.getTopViewedLibrariesAndCounts(ownerId, since, limit) }
  }
}
