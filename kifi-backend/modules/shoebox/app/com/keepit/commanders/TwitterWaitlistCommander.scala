package com.keepit.commanders

import com.google.inject.{ Provider, Singleton, Inject, ImplementedBy }
import com.keepit.commanders.emails.TwitterWaitlistEmailSender
import com.keepit.common.db.{ State, Id }
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.mail.ElectronicMail
import com.keepit.common.time._
import com.keepit.heimdal.HeimdalContextBuilder
import com.keepit.model._
import com.keepit.social.{ SocialNetworks, SocialNetworkType }

import scala.concurrent.Future

@ImplementedBy(classOf[TwitterWaitlistCommanderImpl])
trait TwitterWaitlistCommander {
  def addEntry(userId: Id[User], handle: String): Either[String, (TwitterWaitlistEntry, Option[Future[ElectronicMail]])]
  def getFakeWaitlistPosition(userId: Id[User], handle: String): Option[Long]
  def getFakeWaitlistLength(): Long
  def getWaitlist: Seq[TwitterWaitlistEntry]
  def acceptUser(userId: Id[User], handle: String): Either[String, TwitterSyncState]
}

@Singleton
class TwitterWaitlistCommanderImpl @Inject() (
    db: Database,
    userRepo: UserRepo,
    twitterWaitlistRepo: TwitterWaitlistRepo,
    twitterEmailSender: Provider[TwitterWaitlistEmailSender],
    socialUserInfoRepo: SocialUserInfoRepo,
    libraryCommander: LibraryCommander,
    twitterSyncCommander: TwitterSyncCommander,
    heimdalContextBuilder: HeimdalContextBuilder,
    syncStateRepo: TwitterSyncStateRepo,
    clock: Clock) extends TwitterWaitlistCommander with Logging {

  private val WAITLIST_LENGTH_SHIFT = 1152
  private val WAITLIST_MULTIPLIER = 3

  def addEntry(userId: Id[User], handle: String): Either[String, (TwitterWaitlistEntry, Option[Future[ElectronicMail]])] = {
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
      val (user, savedEntry) = db.readWrite(attempts = 3) { implicit s =>
        val user = userRepo.get(userId)
        val savedEntry = twitterWaitlistRepo.save(entry)
        (user, savedEntry)
      }
      val emailToSend = user.primaryEmail.map { email =>
        twitterEmailSender.get.sendToUser(email, userId)
      }
      (savedEntry, emailToSend)
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

  def getWaitlist: Seq[TwitterWaitlistEntry] = {
    db.readOnlyReplica { implicit session =>
      twitterWaitlistRepo.getPending
    }
  }

  def acceptUser(userId: Id[User], handle: String): Either[String, TwitterSyncState] = {
    val (entryOpt, suiOpt, syncOpt) = db.readWrite { implicit session =>
      val entryOpt = twitterWaitlistRepo.getByUserAndHandle(userId, handle)
      val suiOpt = socialUserInfoRepo.getByUser(userId).find(_.networkType == SocialNetworks.TWITTER)
      val syncOpt = syncStateRepo.getByHandleAndUserIdUsed(handle, userId)
      (entryOpt, suiOpt, syncOpt)
    }

    (entryOpt, suiOpt, syncOpt) match {
      case (Some(entry), Some(sui), None) if entry.state == TwitterWaitlistEntryStates.ACTIVE && sui.credentials.isDefined && sui.userId.isDefined =>
        val addRequest = LibraryAddRequest(
          name = s"Interesting links from @$handle",
          visibility = LibraryVisibility.PUBLISHED,
          slug = s"interesting-links-from-$handle",
          kind = Some(LibraryKind.USER_CREATED), // bad!
          description = Some(s"Interesting Articles and Links I've shared: https://twitter.com/$handle"),
          color = Some(LibraryColor.pickRandomLibraryColor()),
          listed = Some(true)
        )
        implicit val context = heimdalContextBuilder.build
        libraryCommander.addLibrary(addRequest, userId).fold({ fail =>
          Left(fail.message)
        }, { lib =>
          twitterWaitlistRepo.save(entry.copy(state = TwitterWaitlistEntryStates.ACCEPTED))
          val sync = twitterSyncCommander.internTwitterSync(Some(sui.userId.get), lib.id.get, handle)
          twitterSyncCommander.syncOne(Some(sui), sync, sui.userId.get)
          Right(sync)
        })
      case _ =>
        // invalid
        Left(s"Couldn't accept $userId. $entryOpt, $suiOpt, $syncOpt")
    }
  }
}

