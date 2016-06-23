package com.keepit.commanders

import java.io.FileOutputStream

import com.google.inject.{ Provider, Singleton, Inject, ImplementedBy }
import com.keepit.common.concurrent.ReactiveLock
import com.keepit.common.crypto.RatherInsecureDESCrypt
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.{ ExternalId, State, Id }
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.oauth.{ OAuth1TokenInfo, TwitterUserShow, TwitterOAuthProvider }
import com.keepit.common.service.RequestConsolidator
import com.keepit.common.time._
import com.keepit.controllers.ext.ExtLibraryController
import com.keepit.heimdal.HeimdalContextBuilder
import com.keepit.model._
import com.keepit.social.twitter.TwitterHandle
import com.keepit.social.{ SocialGraphPlugin, SocialNetworks, SocialNetworkType }
import org.joda.time.DateTime
import play.api.libs.Files.TemporaryFile
import play.api.libs.iteratee.Iteratee
import play.api.libs.ws.WS
import play.api.Play.current
import scala.concurrent.duration._

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Try, Success }

@ImplementedBy(classOf[TwitterWaitlistCommanderImpl])
trait TwitterWaitlistCommander {
  def createSyncOrWaitlist(userId: Id[User], target: SyncTarget): Either[String, Either[TwitterWaitlistEntry, TwitterSyncState]]
  def processQueue(): Unit
  def syncTwitterShow(handle: TwitterHandle, sui: SocialUserInfo, libraryId: Id[Library]): Future[Option[TwitterUserShow]]

  def addUserToWaitlist(userId: Id[User], handle: Option[TwitterHandle]): Either[String, TwitterWaitlistEntry]
  def getFakeWaitlistPosition(userId: Id[User], handle: TwitterHandle): Option[Long]
  def getFakeWaitlistLength(): Long
  def getWaitlist: Seq[(TwitterWaitlistEntry, Option[TwitterSyncState])]
  def acceptUser(userId: Id[User], handle: TwitterHandle): Either[String, TwitterSyncState]

  def getSyncKey(userExtId: ExternalId[User]): String
  def getUserFromSyncKey(urlKey: String): Option[ExternalId[User]]
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
    socialGraphPlugin: SocialGraphPlugin,
    implicit val executionContext: ExecutionContext) extends TwitterWaitlistCommander with Logging {

  private val WAITLIST_LENGTH_SHIFT = 1152
  private val WAITLIST_MULTIPLIER = 3

  // Assumes users only ever have one sync. ErrorStr | Entry | Sync
  def createSyncOrWaitlist(userId: Id[User], target: SyncTarget): Either[String, Either[TwitterWaitlistEntry, TwitterSyncState]] = {
    db.readWrite(attempts = 3) { implicit s =>
      twitterSyncStateRepo.getByUserIdUsed(userId).find(_.target == target) match {
        case Some(sync) =>
          Left(Right(sync))
        case None if target == SyncTarget.Tweets =>
          val handle = inferHandle(userId)
          val exitingWaitlist = twitterWaitlistRepo.getByUser(userId).find(_.state == TwitterWaitlistEntryStates.ACTIVE) match {
            case Some(wl) if wl.twitterHandle.isEmpty && handle.nonEmpty => Some(twitterWaitlistRepo.save(wl.copy(twitterHandle = handle)))
            case None if handle.isEmpty =>
              usersAnyTwitterSui(userId).foreach(socialGraphPlugin.asyncFetch(_, broadcastToOthers = true))
              None
            case other => other
          }
          Right(Right(exitingWaitlist.getOrElse {
            twitterWaitlistRepo.save(TwitterWaitlistEntry(userId = userId, twitterHandle = handle))
          }))
        case None =>
          (for { handle <- inferHandle(userId); sui <- usersFullTwitterSui(userId) } yield {
            (handle, sui)
          }) match {
            case Some(sh) => Right(Left(sh))
            case None => Left(Left("twitter_not_synced"))
          }
      }
    } match {
      // Creating tweets sync, waitlist works, requires handle to continue:
      case Right(Right(waitlist)) if waitlist.twitterHandle.isDefined =>
        createSyncFromWaitlist(waitlist.id.get).right.map(t => Right(t))
      case Right(Right(waitlist)) =>
        Right(Left(waitlist))
      // Favorites sync, requires handle:
      case Right(Left(sh)) =>
        createSync(userId, sh._2, sh._1, target, None).right.map(t => Right(t))
      // Sync exists already:
      case Left(Right(sync)) =>
        Right(Right(sync))
      case Left(Left(error)) =>
        Left(error)
    }
  }

  // Goes through un-accepted waitlisted users, sees if we can turn it on for them now.
  private val processLock = new ReactiveLock(1)
  def processQueue(): Unit = {
    if (processLock.waiting + processLock.running > 0) {
      log.info(s"[processQueue] Already going. ${processLock.running} ${processLock.waiting}")
    } else {
      processLock.withLock {
        log.info(s"[processQueue] Checking")
        db.readOnlyReplica { implicit session =>
          val pending = twitterWaitlistRepo.getPending.sortBy(_.createdAt)(implicitly[Ordering[DateTime]].reverse)
          pending.toStream.filter { p =>
            usersAnyTwitterSui(p.userId).isDefined
          }.take(10).toList // Not super efficient but fine for now, especially for testing
        }.map { p =>
          log.info(s"[processQueue] Creating sync for ${p.userId}")
          createSyncFromWaitlist(p.id.get)
        }
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
      val syncOpt = twitterSyncStateRepo.getByHandleAndUserIdUsed(handle, userId, SyncTarget.Tweets)
      (entryOpt, suiOpt, syncOpt)
    }

    (entryOpt, suiOpt, syncOpt) match {
      case (Some(entry), Some(sui), None) if entry.state == TwitterWaitlistEntryStates.ACTIVE && sui.credentials.isDefined && sui.userId.isDefined =>
        createSyncFromWaitlist(entry.id.get)
      case _ =>
        // invalid
        Left(s"Couldn't accept User $userId. Entry: $entryOpt, SocialUserInfo: $suiOpt, SyncState: $syncOpt")
    }
  }

  private def inferHandle(userId: Id[User])(implicit session: RSession) = {
    usersFullTwitterSui(userId).flatMap(_.username.map(TwitterHandle(_)))
  }

  private def usersAnyTwitterSui(userId: Id[User])(implicit session: RSession) = {
    socialUserInfoRepo.getByUser(userId)
      .filter(s => s.networkType == SocialNetworks.TWITTER)
      .lastOption
  }

  private def usersFullTwitterSui(userId: Id[User])(implicit session: RSession) = {
    socialUserInfoRepo.getByUser(userId)
      .filter(s => s.networkType == SocialNetworks.TWITTER && s.username.isDefined && s.state == SocialUserInfoStates.FETCHED_USING_SELF)
      .lastOption
  }

  private val refreshSocial = new RequestConsolidator[Id[SocialUserInfo], Unit](15.minutes)
  private def createSyncFromWaitlist(entryId: Id[TwitterWaitlistEntry]) = {
    val (entry, suiAndHandle) = db.readOnlyMaster { implicit session =>
      val entry = twitterWaitlistRepo.get(entryId)
      val suiAndHandle = if (entry.state == TwitterWaitlistEntryStates.ACTIVE) {
        usersFullTwitterSui(entry.userId).map { sui =>
          (sui, entry.twitterHandle.orElse(sui.username.map(TwitterHandle(_))))
        }
      } else {
        None
      }
      (entry, suiAndHandle)
    }
    suiAndHandle match {
      case Some((sui, Some(handle))) if entry.state == TwitterWaitlistEntryStates.ACTIVE =>
        createSync(entry.userId, sui, handle, SyncTarget.Tweets, Some(entry))
      case Some((sui, None)) if sui.createdAt.isBefore(clock.now.minusMinutes(2)) && sui.createdAt.isAfter(clock.now.minusMinutes(33)) => // We don't know who this is. Try refetching again.
        refreshSocial(sui.id.get) { _ =>
          log.info(s"[createSync] Attempting to refetch SUI for ${entry.id.get}, userId: ${entry.userId}")
          socialGraphPlugin.asyncFetch(sui, broadcastToOthers = true)
        }
        Left(s"[createSync] Nothing to do for ${entry.id.get}, userId: ${entry.userId} because we have no handle.")
      case _ =>
        Left(s"[createSync] Nothing to do for ${entry.id.get}, userId: ${entry.userId}, handle: ${suiAndHandle.flatMap(_._2)}")
    }
  }

  private def createSync(userId: Id[User], sui: SocialUserInfo, handle: TwitterHandle, target: SyncTarget, entryOpt: Option[TwitterWaitlistEntry]): Either[String, TwitterSyncState] = {
    val (titleNoun, slugNoun, actionVerb) = target match {
      case SyncTarget.Favorites => ("Favorites", "favorites", "favorited")
      case SyncTarget.Tweets => ("Links", "links", "shared")
    }

    val addRequest = LibraryInitialValues(
      name = s"@${handle.value}’s Twitter $titleNoun",
      visibility = LibraryVisibility.PUBLISHED,
      slug = Some(s"${handle.value}-twitter-$slugNoun"),
      kind = Some(LibraryKind.USER_CREATED), // bad!
      description = Some(s"Interesting pages, articles, and links I've $actionVerb on Twitter: https://twitter.com/${handle.value}"),
      color = Some(LibraryColor.pickRandomLibraryColor()),
      listed = Some(true)
    )
    implicit val context = heimdalContextBuilder.build
    libraryCommander.createLibrary(addRequest, userId).fold({ fail =>
      Left(fail.message)
    }, { lib =>
      entryOpt.foreach { entry =>
        db.readWrite { implicit session =>
          twitterWaitlistRepo.save(entry.copy(state = TwitterWaitlistEntryStates.ACCEPTED, twitterHandle = Some(handle)))
        }
      }
      val sync = twitterSyncCommander.internTwitterSync(Some(userId), lib.id.get, handle, target)
      log.info(s"[createSync] Sync created for $userId, ${handle.value}")
      syncTwitterShow(handle, sui, lib.id.get).andThen {
        case _ => // Always sync, even if show failed to update
          twitterSyncCommander.syncOne(Some(sui), sync, sui.userId.get)
      }

      Right(sync)
    })
  }

  // Move to TwitterSyncCommander so that we can call this periodically during syncs?
  def syncTwitterShow(handle: TwitterHandle, sui: SocialUserInfo, libraryId: Id[Library]) = {
    getAndUpdateUserShow(sui, handle).andThen {
      case Success(Some(show)) => updateLibraryFromShow(sui.userId.get, libraryId, show)
      case fail => log.info(s"[twc-acceptUser] Couldn't get show for ${sui.userId}, $fail")
    }
  }

  private def getAndUpdateUserShow(sui: SocialUserInfo, handle: TwitterHandle): Future[Option[TwitterUserShow]] = {
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
          if (sui.username.exists(_ == handle.value)) {
            show.profile_banner_url.foreach { img =>
              userValueRepo.setValue(userId, UserValueName.TWITTER_BANNER_IMAGE, img)
            }
            show.statuses_count.foreach { cnt =>
              userValueRepo.setValue(userId, UserValueName.TWITTER_STATUSES_COUNT, cnt)
            }
            show.favourites_count.foreach { cnt =>
              userValueRepo.setValue(userId, UserValueName.TWITTER_FAVOURITES_COUNT, cnt)
            }
            show.description.foreach { descr =>
              userValueRepo.setValue(userId, UserValueName.TWITTER_DESCRIPTION, descr)
            }
            show.followers_count.foreach { count =>
              userValueRepo.setValue(userId, UserValueName.TWITTER_FOLLOWERS_COUNT, count)
            }
            show.`protected`.foreach { protectedProfile =>
              userValueRepo.setValue(userId, UserValueName.TWITTER_PROTECTED_ACCOUNT, protectedProfile)
            }
          }
        }
        Some(show)
      }
    }
    result.getOrElse(Future.successful(None))
  }

  private def updateLibraryFromShow(userId: Id[User], libraryId: Id[Library], show: TwitterUserShow) = {
    log.info(s"[createSync] Got show for $userId, $show")

    db.readWrite(attempts = 3) { implicit session =>
      // Update lib's description if it's the default
      show.description.foreach { desc =>
        val lib = libraryRepo.getNoCache(libraryId)
        if (lib.description.isEmpty || lib.description.exists(_.indexOf("Interesting pages") == 0)) {
          libraryRepo.save(libraryRepo.getNoCache(libraryId).copy(description = Option(desc).filter(_.nonEmpty)))
          log.info(s"[updateLibraryFromShow] $libraryId ($userId): Description updated to ${Option(desc).filter(_.nonEmpty)}")
        }
      }

      // Update visibility, only reducing visibility
      show.`protected`.foreach { protectedProfile =>
        if (protectedProfile) {
          libraryRepo.save(libraryRepo.getNoCache(libraryId).copy(visibility = LibraryVisibility.SECRET))
          log.info(s"[updateLibraryFromShow] $libraryId ($userId): Visibility updated to $protectedProfile")
        }
      }
    }

    // Update library picture
    show.profile_banner_url.foreach { image =>
      val existing = db.readOnlyReplica { implicit session =>
        libraryImageCommander.getBestImageForLibrary(libraryId, ExtLibraryController.defaultImageSize)
      }
      if (existing.isEmpty) {
        syncPic(userId, libraryId, image)
        log.info(s"[updateLibraryFromShow] $libraryId ($userId): Image updated to $image")
      }
    }
  }

  private def syncPic(userId: Id[User], libraryId: Id[Library], bannerPic: String) = {
    // Twitter's return URL is actually incomplete. You need to specify a size. We'll use the largest.
    val imageUrl = bannerPic + "/1500x500"
    WS.url(imageUrl).getStream().flatMap {
      case (headers, streamBody) =>
        if (headers.status != 200) {
          Future.failed(new RuntimeException(s"Image returned non-200 code, ${headers.status}, $imageUrl"))
        } else {
          val tempFile = TemporaryFile(prefix = s"tw-${userId.id}-${libraryId.id}")
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

  private val crypt = new RatherInsecureDESCrypt
  private val key = crypt.stringToKey("TwittersyncWhoooooaaaaaH")
  def getSyncKey(userExtId: ExternalId[User]): String = {
    crypt.crypt(key, userExtId.id).trim()
  }

  def getUserFromSyncKey(urlKey: String): Option[ExternalId[User]] = {
    crypt.decrypt(key, urlKey).map(_.trim()).toOption.flatMap(ExternalId.asOpt[User])
  }
}

