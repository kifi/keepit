package com.keepit.commanders

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.db.slick.Database
import com.keepit.model.{ LibraryPathHelper, Library, UserRepo }

@Singleton
class LibraryPathCommander @Inject() (
    db: Database,
    userRepo: UserRepo) extends LibraryPathHelper {
  def getPath(lib: Library): String = {
    val user = db.readOnlyMaster { implicit s => userRepo.get(lib.ownerId) }
    formatLibraryPath(user.username, lib.slug)
  }

  def getPathUrlEncoded(lib: Library): String = {
    val user = db.readOnlyMaster { implicit s => userRepo.get(lib.ownerId) }
    formatLibraryPathUrlEncoded(user.username, lib.slug)
  }
}
