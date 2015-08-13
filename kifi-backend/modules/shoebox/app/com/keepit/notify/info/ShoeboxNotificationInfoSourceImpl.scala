package com.keepit.notify.info

import com.google.inject.Inject
import com.keepit.commanders.{ UserCommander, LibraryCommander, PathCommander }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.path.EncodedPath
import com.keepit.model.{ UserRepo, LibraryRepo, Library, User }
import com.keepit.notify.model.NotificationEvent

import scala.concurrent.Future
import scala.util.{ Failure, Try }

class ShoeboxNotificationInfoSourceImpl @Inject() (
    db: Database,
    userRepo: UserRepo,
    userCommander: UserCommander,
    libraryRepo: LibraryRepo,
    pathCommander: PathCommander) extends NotificationInfoSource {

  override def userImage(id: Id[User], width: Int): Future[String] = userCommander.getUserImageUrl(id, width)

  override def libraryPath(id: Id[Library]): Future[EncodedPath] = {
    val library = db.readOnlyReplica { implicit session =>
      libraryRepo.get(id)
    }
    Future.successful(pathCommander.pathForLibrary(library).encode)
  }

  override def user(id: Id[User]): Future[User] = {
    val user = db.readOnlyReplica { implicit session =>
      userRepo.get(id)
    }
    Future.successful(user)
  }

  override def library(id: Id[Library]): Future[Library] = {
    val library = db.readOnlyReplica { implicit session =>
      libraryRepo.get(id)
    }
    Future.successful(library)
  }

}
