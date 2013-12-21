package com.keepit.commanders

import com.keepit.common.db.Id
import com.keepit.model.User
import com.google.inject.Inject
import com.keepit.model.BookmarkRepo
import com.keepit.common.db.slick.Database

class AbuseControlException(message: String) extends Exception(message)

class KeepsAbuseController @Inject() (
    absoluteAlert: Int,
    absoluteError: Int,
    bookmarkRepo: BookmarkRepo,
    db: Database) {

  if (absoluteAlert >= absoluteError) throw new IllegalStateException(s"absolute alert $absoluteAlert is larger then error $absoluteError")

  implicit val dbMasterSlave = Database.Slave

  def inspact(userId: Id[User], newKeepCount: Int): Unit = {
    val existingBookmarksCount = db.readOnly { implicit s => bookmarkRepo.getCountByUser(userId) }
    if (existingBookmarksCount > absoluteError) {
      throw new AbuseControlException(s"user $userId tried to add $newKeepCount keeps while having $existingBookmarksCount. max allowed is $absoluteError")
    }
  }

}
