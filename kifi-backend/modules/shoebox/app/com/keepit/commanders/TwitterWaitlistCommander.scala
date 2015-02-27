package com.keepit.commanders

import com.google.inject.{ Singleton, Inject, ImplementedBy }
import com.keepit.common.db.{ State, Id }
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.model.{ TwitterWaitlistEntryStates, TwitterWaitlistRepo, User, TwitterWaitlistEntry }

@ImplementedBy(classOf[TwitterWaitlistCommanderImpl])
trait TwitterWaitlistCommander {
  def addEntry(userId: Id[User], handle: String): Either[String, TwitterWaitlistEntry]
  def getFakeWaitlistPosition(userId: Id[User], handle: String): Option[Long]
  def getFakeWaitlistLength(): Long
}

@Singleton
class TwitterWaitlistCommanderImpl @Inject() (
    db: Database,
    twitterWaitlistRepo: TwitterWaitlistRepo,
    clock: Clock) extends TwitterWaitlistCommander with Logging {

  private val WAITLIST_LENGTH_SHIFT = 1152
  private val WAITLIST_MULTIPLIER = 3

  def addEntry(userId: Id[User], handle: String): Either[String, TwitterWaitlistEntry] = {
    val waitlistEntry = db.readOnlyMaster { implicit s =>
      twitterWaitlistRepo.getByUserAndHandle(userId, handle)
    }

    val entryOpt = if (waitlistEntry.isEmpty) {
      Right(TwitterWaitlistEntry(userId = userId, twitterHandle = handle))
    } else {
      val targetEntry = waitlistEntry.get
      if (targetEntry.state == TwitterWaitlistEntryStates.INACTIVE) {
        Right(targetEntry.withState(TwitterWaitlistEntryStates.ACTIVE))
      } else { // state is active or accepted
        Left("entry_already_active")
      }
    }
    entryOpt.right.map { entry =>
      db.readWrite { implicit s =>
        twitterWaitlistRepo.save(entry)
      }
    }
  }

  def getFakeWaitlistPosition(userId: Id[User], handle: String): Option[Long] = {
    db.readOnlyMaster { implicit session =>
      twitterWaitlistRepo.getByUserAndHandle(userId, handle).map { waitlistEntry =>
        twitterWaitlistRepo.countActiveEntriesBeforeDateTime(waitlistEntry.createdAt)
      }
    }.map(_ * WAITLIST_MULTIPLIER + WAITLIST_LENGTH_SHIFT)
  }

  def getFakeWaitlistLength(): Long = {
    db.readOnlyReplica { implicit session =>
      twitterWaitlistRepo.countActiveEntriesBeforeDateTime(currentDateTime)
    } * WAITLIST_MULTIPLIER + WAITLIST_LENGTH_SHIFT
  }
}

