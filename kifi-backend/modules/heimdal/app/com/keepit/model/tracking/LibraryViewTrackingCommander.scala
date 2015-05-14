package com.keepit.model.tracking

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.controllers.UserEventHandler
import com.keepit.heimdal.{ UserEventTypes, UserEvent }
import com.keepit.model.{ Library, User }

@Singleton
class LibraryViewTrackingCommander @Inject() (
    db: Database,
    libViewRepo: LibraryViewTrackingRepo) extends UserEventHandler {

  def handleUserEvent(event: UserEvent): Unit = {
    event match {
      case UserEvent(userId, context, UserEventTypes.VIEWED_LIBRARY, time) =>
        for {
          owner <- context.get[String]("ownerId")
          libId <- context.get[String]("libraryId")
        } yield {
          val viewerId = context.get[String]("viewerId").map { x => Id[User](x.toLong) }
          db.readWrite { implicit s => libViewRepo.save(LibraryViewTracking(ownerId = Id[User](owner.toLong), libraryId = Id[Library](libId.toLong), viewerId = viewerId, source = LibraryViewSource.fromContext(context))) }
        }

      case _ =>
    }
  }
}
