package com.keepit.common.store

import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.model.{PutObjectResult, ObjectMetadata}
import com.google.inject.Inject
import com.google.inject.Singleton
import com.keepit.common.controller.ActionAuthenticator
import com.keepit.common.controller.WebsiteController
import com.keepit.common.db.ExternalId
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.HealthcheckError
import com.keepit.common.healthcheck.{HealthcheckPlugin, Healthcheck}
import com.keepit.common.social.SocialNetworks
import com.keepit.common.time._
import com.keepit.common.time.parseStandardTime
import com.keepit.model.SocialUserInfo
import com.keepit.model.SocialUserInfoRepo
import com.keepit.model.User
import com.keepit.model.UserValueRepo
import org.joda.time.Weeks
import play.api.libs.ws.WS
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.promise
import scala.util.{Success, Failure}

@Singleton
class S3ImageStore @Inject() (
    actionAuthenticator: ActionAuthenticator,
    db: Database,
    userValueRepo: UserValueRepo,
    config: S3ImageConfig,
    s3Client: AmazonS3,
    suiRepo: SocialUserInfoRepo,
    healthcheckPlugin: HealthcheckPlugin,
    clock: Clock)
    extends WebsiteController(actionAuthenticator) {

  private val UserPictureLastUpdatedKey = "user_picture_last_updated"
  private val ExpirationTime = Weeks.ONE

  val cdnBase: String = s"//${config.cloudfrontHost}"

  def getPictureUrl(width: Int, user: User): Future[String] = {
    val sui = db.readOnly { implicit s => suiRepo.getByUser(user.id.get).head }
    db.readOnly { implicit s => userValueRepo.getValue(user.id.get, UserPictureLastUpdatedKey) }.map { s =>
      parseStandardTime(s).isAfter(clock.now().minus(ExpirationTime))
    } match {
      case None =>
        // No picture uploaded, wait for it
        updatePicture(sui, user.externalId).map { _ =>
          config.avatarUrlByExternalId(width, user.externalId)
        }
      case Some(upToDate) =>
        // We have an image so serve that one, even if it might be outdated
        if (!upToDate) updatePicture(sui, user.externalId)
        promise[String]().success(config.avatarUrlByExternalId(width, user.externalId)).future
    }
  }

  private def avatarUrlFromSocialNetwork(sui: SocialUserInfo, size: Int): String = {
    if (sui.networkType != SocialNetworks.FACEBOOK) {
      throw new UnsupportedOperationException("We only support facebook right now")
    }
    s"https://graph.facebook.com/${sui.socialId.id}/picture?width=$size&height=$size"
  }

  private def updatePicture(sui: SocialUserInfo, externalId: ExternalId[User]): Future[Seq[PutObjectResult]] = {
    val future = Future.sequence(for {
      size <- S3ImageConfig.ImageSizes
      userId <- sui.userId
    } yield {
      val originalImageUrl = avatarUrlFromSocialNetwork(sui, size)
      WS.url(originalImageUrl).get().map { response =>
        val key = config.keyByExternalId(size, externalId)
        log.info(s"Uploading picture $originalImageUrl to S3 key $key")
        val om = new ObjectMetadata()
        om.setContentType("image/jpeg")
        s3Client.putObject(config.bucketName, key, response.getAHCResponse.getResponseBodyAsStream, om)
      }
    })
    future onComplete {
      case Success(_) =>
        db.readWrite { implicit s =>
          userValueRepo.setValue(
            sui.userId.get, UserPictureLastUpdatedKey,
            clock.now().toStandardTimeString)
        }
      case Failure(e) =>
        healthcheckPlugin.addError(HealthcheckError(
          error = Some(e),
          callType = Healthcheck.INTERNAL,
          errorMessage = Some("Failed to upload picture to S3")
        ))
    }
    future
  }
}
