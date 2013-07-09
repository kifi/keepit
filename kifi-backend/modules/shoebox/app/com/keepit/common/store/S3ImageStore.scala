package com.keepit.common.store

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.util.{Success, Failure}
import org.joda.time.Weeks
import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.model.{PutObjectResult, ObjectMetadata}
import com.google.inject.{ImplementedBy, Inject, Singleton}
import com.keepit.common.controller.ActionAuthenticator
import com.keepit.common.db.ExternalId
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.HealthcheckError
import com.keepit.common.healthcheck.{HealthcheckPlugin, Healthcheck}
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.common.time.parseStandardTime
import com.keepit.model.SocialUserInfo
import com.keepit.model.SocialUserInfoRepo
import com.keepit.model.User
import com.keepit.model.UserValueRepo
import play.api.libs.ws.WS
import com.keepit.common.net.URI
import com.keepit.social.SocialNetworks

object S3UserPictureConfig {
  val ImageSizes = Seq(100, 200)
}

@ImplementedBy(classOf[S3ImageStoreImpl])
trait S3ImageStore {
  def config: S3ImageConfig
  def getPictureUrl(width: Int, user: User): Future[String]
  def updatePicture(sui: SocialUserInfo, externalId: ExternalId[User]): Future[Seq[PutObjectResult]]
  
  def avatarUrlByExternalId(w: Int, userId: ExternalId[User], protocolDefault: Option[String] = None): String = {
    val size = S3UserPictureConfig.ImageSizes.find(_ >= w).getOrElse(S3UserPictureConfig.ImageSizes.last)
    val uri = URI.parse(s"${config.cdnBase}/${keyByExternalId(size, userId)}").get
    URI(uri.scheme orElse protocolDefault, uri.userInfo, uri.host, uri.port, uri.path, uri.query, uri.fragment).toString
  }

  def keyByExternalId(size: Int, userId: ExternalId[User]): String =
    s"users/$userId/pics/$size/0.jpg"
}

@Singleton
class S3ImageStoreImpl @Inject() (
    actionAuthenticator: ActionAuthenticator,
    db: Database,
    userValueRepo: UserValueRepo,
    s3Client: AmazonS3,
    suiRepo: SocialUserInfoRepo,
    healthcheckPlugin: HealthcheckPlugin,
    clock: Clock,
    val config: S3ImageConfig
  ) extends S3ImageStore with Logging {

  private val UserPictureLastUpdatedKey = "user_picture_last_updated"
  private val ExpirationTime = Weeks.ONE

  def getPictureUrl(width: Int, user: User): Future[String] = {
    val sui = db.readOnly { implicit s => suiRepo.getByUser(user.id.get).head }
    if (config.isLocal) {
      Promise.successful(avatarUrlFromSocialNetwork(sui, width)).future
    } else {
      db.readOnly { implicit s => userValueRepo.getValue(user.id.get, UserPictureLastUpdatedKey) }.map { s =>
        parseStandardTime(s).isAfter(clock.now().minus(ExpirationTime))
      } match {
        case None =>
          // No picture uploaded, wait for it
          updatePicture(sui, user.externalId).map { _ =>
            avatarUrlByExternalId(width, user.externalId)
          }
        case Some(upToDate) =>
          // We have an image so serve that one, even if it might be outdated
          if (!upToDate) updatePicture(sui, user.externalId)
          Promise.successful(avatarUrlByExternalId(width, user.externalId)).future
      }
    }
  }

  private def avatarUrlFromSocialNetwork(sui: SocialUserInfo, size: Int): String = {
    sui.getPictureUrl(size, size).getOrElse(
      "http://s.c.lnkd.licdn.com/scds/common/u/images/themes/katy/ghosts/person/ghost_person_200x200_v1.png")
  }

  def updatePicture(sui: SocialUserInfo, externalId: ExternalId[User]): Future[Seq[PutObjectResult]] = {
    if (config.isLocal) {
      Promise.successful(Seq()).future
    } else {
      val future = Future.sequence(for {
        size <- S3UserPictureConfig.ImageSizes
        userId <- sui.userId
      } yield {
        val originalImageUrl = avatarUrlFromSocialNetwork(sui, size)
        WS.url(originalImageUrl).get().map { response =>
          val key = keyByExternalId(size, externalId)
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
}
