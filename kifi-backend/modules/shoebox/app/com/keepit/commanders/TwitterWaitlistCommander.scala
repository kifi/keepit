package com.keepit.commanders

import java.io.FileOutputStream

import com.google.inject.{ Provider, Singleton, Inject, ImplementedBy }
import com.keepit.commanders.emails.TwitterWaitlistEmailSender
import com.keepit.common.db.{ State, Id }
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.mail.ElectronicMail
import com.keepit.common.oauth.{ TwitterUserShow, TwitterOAuthProvider }
import com.keepit.common.time._
import com.keepit.heimdal.HeimdalContextBuilder
import com.keepit.model._
import com.keepit.social.{ SocialNetworks, SocialNetworkType }
import play.api.libs.Files.TemporaryFile
import play.api.libs.iteratee.Iteratee
import play.api.libs.ws.WS
import play.api.Play.current

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.Success

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
    libraryImageCommander: LibraryImageCommander,
    twitterSyncCommander: TwitterSyncCommander,
    heimdalContextBuilder: HeimdalContextBuilder,
    syncStateRepo: TwitterSyncStateRepo,
    twitterOAuthProvider: TwitterOAuthProvider,
    libraryRepo: LibraryRepo,
    clock: Clock,
    userValueRepo: UserValueRepo,
    implicit val executionContext: ExecutionContext) extends TwitterWaitlistCommander with Logging {

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
          db.readWrite { implicit session =>
            twitterWaitlistRepo.save(entry.copy(state = TwitterWaitlistEntryStates.ACCEPTED))
          }
          val sync = twitterSyncCommander.internTwitterSync(Some(sui.userId.get), lib.id.get, handle)

          updateUserShow(sui, handle).andThen {
            case Success(Some(show)) =>
              log.info(s"[twc-acceptUser] Got show for $userId, $show")

              // Update lib's description
              db.readWrite { implicit session =>
                libraryRepo.save(libraryRepo.get(lib.id.get).copy(description = show.description))
              }

              // Update library picture
              show.profile_banner_url.map { image =>
                syncPic(userId, handle, lib.id.get, image)
              }

            case fail =>
              log.info(s"[twc-acceptUser] Couldn't get show for $userId, $fail")
          }.andThen {
            case _ =>
              twitterSyncCommander.syncOne(Some(sui), sync, sui.userId.get)
          }

          Right(sync)
        })
      case _ =>
        // invalid
        Left(s"Couldn't accept $userId. $entryOpt, $suiOpt, $syncOpt")
    }
  }

  private def updateUserShow(sui: SocialUserInfo, handle: String): Future[Option[TwitterUserShow]] = {
    val result = for {
      cred <- sui.credentials
      oauth <- cred.oAuth1Info
      userId <- sui.userId
    } yield {
      twitterOAuthProvider.getUserShow(OAuth1TokenInfo.fromOAuth1Info(oauth), handle).map { show =>
        db.readWrite { implicit session =>
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

  private def syncPic(userId: Id[User], handle: String, libraryId: Id[Library], bannerPic: String) = {
    // Twitter's return URL is actually incomplete. You need to specify a size. We'll use the largest.
    val imageUrl = bannerPic + "/1500x500"
    WS.url(imageUrl).getStream().flatMap {
      case (headers, streamBody) =>
        if (headers.status != 200) {
          Future.failed(new RuntimeException(s"Image returned non-200 code, ${headers.status}, $imageUrl"))
        } else {
          val tempFile = TemporaryFile(prefix = "remote-file")
          tempFile.file.deleteOnExit()
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
      libraryImageCommander.uploadLibraryImageFromFile(imageFile, libraryId, LibraryImagePosition(None, None), ImageSource.TwitterSync, userId, None)
    }
  }
}

