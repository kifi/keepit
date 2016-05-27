package com.keepit.commanders

import java.io.FileOutputStream

import com.google.inject.{ Provider, Singleton, Inject, ImplementedBy }
import com.keepit.common.concurrent.ReactiveLock
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.{ State, Id }
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.oauth.{ OAuth1TokenInfo, TwitterUserShow, TwitterOAuthProvider }
import com.keepit.common.time._
import com.keepit.heimdal.HeimdalContextBuilder
import com.keepit.model._
import com.keepit.social.twitter.TwitterHandle
import com.keepit.social.{ SocialNetworks, SocialNetworkType }
import org.joda.time.DateTime
import play.api.libs.Files.TemporaryFile
import play.api.libs.iteratee.Iteratee
import play.api.libs.ws.WS
import play.api.Play.current

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Try, Success }

@ImplementedBy(classOf[TwitterWaitlistCommanderImpl])
trait TwitterWaitlistCommander {
  def createSyncOrWaitlist(userId: Id[User]): Either[String, Either[TwitterWaitlistEntry, TwitterSyncState]]
  def processQueue(): Unit

  def addUserToWaitlist(userId: Id[User], handle: Option[TwitterHandle]): Either[String, TwitterWaitlistEntry]
  def getFakeWaitlistPosition(userId: Id[User], handle: TwitterHandle): Option[Long]
  def getFakeWaitlistLength(): Long
  def getWaitlist: Seq[(TwitterWaitlistEntry, Option[TwitterSyncState])]
  def acceptUser(userId: Id[User], handle: TwitterHandle): Either[String, TwitterSyncState]
}

@Singleton
class TwitterWaitlistCommanderImpl @Inject() (
    db: Database,
    twitterWaitlistRepo: TwitterWaitlistRepo,
    twitterSyncStateRepo: TwitterSyncStateRepo,
    socialUserInfoRepo: SocialUserInfoRepo,
    libraryCommander: LibraryCommander,
    libraryImageCommander: LibraryImageCommander,
    twitterSyncCommander: TwitterSyncCommander,
    heimdalContextBuilder: HeimdalContextBuilder,
    twitterOAuthProvider: TwitterOAuthProvider,
    libraryRepo: LibraryRepo,
    clock: Clock,
    userValueRepo: UserValueRepo,
    cleanup: ImageCleanup,
    implicit val executionContext: ExecutionContext) extends TwitterWaitlistCommander with Logging {

  private val WAITLIST_LENGTH_SHIFT = 1152
  private val WAITLIST_MULTIPLIER = 3

  // Assumes users only ever have one sync. Error | Entry | Sync
  def createSyncOrWaitlist(userId: Id[User]): Either[String, Either[TwitterWaitlistEntry, TwitterSyncState]] = {
    db.readWrite(attempts = 3) { implicit s =>
      twitterSyncStateRepo.getByUserIdUsed(userId).headOption match {
        case Some(sync) =>
          Left(sync)
        case None =>
          val handle = inferHandle(userId)
          val exitingWaitlist = twitterWaitlistRepo.getByUser(userId).find(_.state == TwitterWaitlistEntryStates.ACTIVE) match {
            case Some(wl) if wl.twitterHandle.isEmpty && handle.nonEmpty => Some(twitterWaitlistRepo.save(wl.copy(twitterHandle = handle)))
            case other => other
          }
          Right(exitingWaitlist.getOrElse {
            twitterWaitlistRepo.save(TwitterWaitlistEntry(userId = userId, twitterHandle = handle))
          })
      }
    } match {
      case Right(waitlist) if waitlist.twitterHandle.isDefined =>
        createSync(waitlist).right.map(t => Right(t))
      case Right(waitlist) =>
        Right(Left(waitlist))
      case Left(sync) =>
        Right(Right(sync))
    }
  }

  // Goes through un-accepted waitlisted users, sees if we can turn it on for them now.
  private val processLock = new ReactiveLock(1)
  def processQueue(): Unit = {
    processLock.withLock {
      db.readOnlyReplica { implicit session =>
        val pending = twitterWaitlistRepo.getPending.sortBy(_.createdAt)(implicitly[Ordering[DateTime]].reverse)
        pending.toStream.filter { p =>
          usersTwitterSui(p.userId).isDefined
        }.take(10).toList // Not super efficient but fine for now, especially for testing
      }.map { p =>
        log.info(s"[processQueue] Creating sync for ${p.userId}")
        createSync(p)
      }
    }
  }

  // Won't be needed anymore:
  def addUserToWaitlist(userId: Id[User], handleOpt: Option[TwitterHandle]): Either[String, TwitterWaitlistEntry] = {
    val (waitlistEntry, handleToUse) = db.readOnlyMaster { implicit s =>
      handleOpt match {
        case Some(handle) =>
          (twitterWaitlistRepo.getByUserAndHandle(userId, handle), Some(handle))
        case None =>
          twitterWaitlistRepo.getByUser(userId).sortBy(_.createdAt).lastOption match {
            case Some(existing) =>
              (Some(existing), existing.twitterHandle)
            case None =>
              (None, inferHandle(userId))
          }
      }
    }

    val entryOpt = waitlistEntry match {
      case None =>
        Right(TwitterWaitlistEntry(userId = userId, twitterHandle = handleToUse))
      case Some(entry) if entry.state == TwitterWaitlistEntryStates.INACTIVE =>
        Right(entry.withState(TwitterWaitlistEntryStates.ACTIVE))
      case Some(entry) =>
        Left("entry_already_active")
    }

    entryOpt.right.map { entry =>
      db.readWrite(attempts = 3) { implicit s =>
        twitterWaitlistRepo.save(entry)
      }
    }
  }

  // Won't be needed anymore:
  def getFakeWaitlistPosition(userId: Id[User], handle: TwitterHandle): Option[Long] = {
    db.readOnlyMaster { implicit session =>
      twitterWaitlistRepo.getByUserAndHandle(userId, handle).map { waitlistEntry =>
        twitterWaitlistRepo.countActiveEntriesBeforeDateTime(waitlistEntry.createdAt)
      }
    }.map(_ * WAITLIST_MULTIPLIER + WAITLIST_LENGTH_SHIFT)
  }

  // Won't be needed anymore:
  def getFakeWaitlistLength(): Long = {
    db.readOnlyReplica { implicit session =>
      twitterWaitlistRepo.countActiveEntriesBeforeDateTime(currentDateTime)
    } * WAITLIST_MULTIPLIER + WAITLIST_LENGTH_SHIFT
  }

  def getWaitlist: Seq[(TwitterWaitlistEntry, Option[TwitterSyncState])] = {
    db.readOnlyReplica { implicit session =>
      val pending = twitterWaitlistRepo.getAdminPage.map { e => e.userId -> e }.toMap
      val syncs = twitterSyncStateRepo.getByUserIds(pending.keySet).filterNot(_.userId.isEmpty).map { e => e.userId.get -> e }.toMap
      val tuples = pending.toSeq.map { case (uid, pend) => pend -> syncs.get(uid) }
      tuples.sortBy(_._1.createdAt).reverse
    }
  }

  // For manual acceptances.
  // Won't be needed anymore:
  def acceptUser(userId: Id[User], handle: TwitterHandle): Either[String, TwitterSyncState] = {
    val (entryOpt, suiOpt, syncOpt) = db.readWrite { implicit session =>
      val entryOpt = twitterWaitlistRepo.getByUserAndHandle(userId, handle)
      val suiOpt = socialUserInfoRepo.getByUser(userId).find(_.networkType == SocialNetworks.TWITTER)
      val syncOpt = twitterSyncStateRepo.getByHandleAndUserIdUsed(handle, userId)
      (entryOpt, suiOpt, syncOpt)
    }

    (entryOpt, suiOpt, syncOpt) match {
      case (Some(entry), Some(sui), None) if entry.state == TwitterWaitlistEntryStates.ACTIVE && sui.credentials.isDefined && sui.userId.isDefined =>
        createSync(entry)
      case _ =>
        // invalid
        Left(s"Couldn't accept User $userId. Entry: $entryOpt, SocialUserInfo: $suiOpt, SyncState: $syncOpt")
    }
  }

  private def inferHandle(userId: Id[User])(implicit session: RSession) = {
    usersTwitterSui(userId).flatMap(_.username.map(TwitterHandle(_)))
  }

  private def usersTwitterSui(userId: Id[User])(implicit session: RSession) = {
    socialUserInfoRepo.getByUser(userId)
      .filter(s => s.networkType == SocialNetworks.TWITTER && s.username.isDefined && s.state == SocialUserInfoStates.FETCHED_USING_SELF)
      .lastOption
  }

  // entry's user must have a valid Twitter SUI, otherwise this no-ops
  private def createSync(entry: TwitterWaitlistEntry): Either[String, TwitterSyncState] = {
    val suiAndHandle = {
      if (entry.state == TwitterWaitlistEntryStates.ACTIVE) {
        db.readOnlyReplica { implicit s =>
          usersTwitterSui(entry.userId)
        }.flatMap { sui =>
          entry.twitterHandle.orElse(sui.username.map(TwitterHandle(_))).map { handle =>
            (sui, handle)
          }
        }
      } else {
        None
      }
    }

    suiAndHandle match {
      case Some((sui, handle)) if entry.state == TwitterWaitlistEntryStates.ACTIVE =>
        val addRequest = LibraryInitialValues(
          name = s"@$handle’s Twitter Links",
          visibility = LibraryVisibility.PUBLISHED,
          slug = Some(s"$handle-twitter-links"),
          kind = Some(LibraryKind.USER_CREATED), // bad!
          description = Some(s"Interesting pages, articles, and links I've shared on Twitter: https://twitter.com/$handle"),
          color = Some(LibraryColor.pickRandomLibraryColor()),
          listed = Some(true)
        )
        implicit val context = heimdalContextBuilder.build
        libraryCommander.createLibrary(addRequest, entry.userId).fold({ fail =>
          Left(fail.message)
        }, { lib =>
          db.readWrite { implicit session =>
            twitterWaitlistRepo.save(entry.copy(state = TwitterWaitlistEntryStates.ACCEPTED, twitterHandle = Some(handle)))
          }
          val sync = twitterSyncCommander.internTwitterSync(Some(entry.userId), lib.id.get, handle, SyncTarget.Tweets)
          log.info(s"[createSync] Sync created for ${entry.userId}, ${handle.value}")

          updateUserShow(sui, handle).andThen {
            case Success(Some(show)) =>
              log.info(s"[createSync] Got show for ${entry.userId}, $show")

              // Update lib's description
              db.readWrite { implicit session =>
                libraryRepo.save(libraryRepo.get(lib.id.get).copy(description = show.description))
              }

              // Update library picture
              show.profile_banner_url.map { image =>
                syncPic(entry.userId, handle, lib.id.get, image)
              }

            case fail =>
              log.info(s"[twc-acceptUser] Couldn't get show for ${entry.userId}, $fail")
          }.andThen {
            case _ =>
              twitterSyncCommander.syncOne(Some(sui), sync, sui.userId.get)
          }

          Right(sync)
        })
      case _ =>
        Left(s"[createSync] Nothing to do for ${entry.id.get}, userId: ${entry.userId}, handle: ${entry.twitterHandle}")
    }
  }

  private def updateUserShow(sui: SocialUserInfo, handle: TwitterHandle): Future[Option[TwitterUserShow]] = {
    val result = for {
      cred <- sui.credentials
      oauth <- cred.oAuth1Info
      userId <- sui.userId
    } yield {
      twitterOAuthProvider.getUserShow(OAuth1TokenInfo.fromOAuth1Info(oauth), handle).map { show =>
        db.readWrite { implicit session =>
          if (sui.state == SocialUserInfoStates.TOKEN_EXPIRED) {
            socialUserInfoRepo.save(sui.copy(state = SocialUserInfoStates.CREATED))
          }
          show.profile_banner_url.foreach { img =>
            userValueRepo.setValue(userId, UserValueName.TWITTER_BANNER_IMAGE, img)
          }
          show.description.foreach { descr =>
            userValueRepo.setValue(userId, UserValueName.TWITTER_DESCRIPTION, descr)
          }
          show.followers_count.foreach { count =>
            userValueRepo.setValue(userId, UserValueName.TWITTER_FOLLOWERS_COUNT, count)
          }
        }
        Some(show)
      }
    }
    result.getOrElse(Future.successful(None))
  }

  private def syncPic(userId: Id[User], handle: TwitterHandle, libraryId: Id[Library], bannerPic: String) = {
    // Twitter's return URL is actually incomplete. You need to specify a size. We'll use the largest.
    val imageUrl = bannerPic + "/1500x500"
    WS.url(imageUrl).getStream().flatMap {
      case (headers, streamBody) =>
        if (headers.status != 200) {
          Future.failed(new RuntimeException(s"Image returned non-200 code, ${headers.status}, $imageUrl"))
        } else {
          val tempFile = TemporaryFile(prefix = s"tw-${handle.value}")
          tempFile.file.deleteOnExit()
          cleanup.cleanup(tempFile.file)
          val outputStream = new FileOutputStream(tempFile.file)

          val maxSize = 1024 * 1024 * 16

          var len = 0
          val iteratee = Iteratee.foreach[Array[Byte]] { bytes =>
            len += bytes.length
            if (len > maxSize) {
              // max original size
              outputStream.close()
              throw new Exception(s"Original image too large (> $len bytes): $imageUrl")
            } else {
              outputStream.write(bytes)
            }
          }

          streamBody.run(iteratee).andThen {
            case _ =>
              outputStream.close()
          } flatMap { _ =>
            Future.successful(tempFile)
          }
        }
    }.flatMap { imageFile =>
      implicit val context = heimdalContextBuilder.build
      libraryImageCommander.uploadLibraryImageFromFile(imageFile.file, libraryId, LibraryImagePosition(None, None), ImageSource.TwitterSync, userId, None).map { _ =>
        imageFile.file // To force imageFile not to be GCed
      }
    }
  }
}

