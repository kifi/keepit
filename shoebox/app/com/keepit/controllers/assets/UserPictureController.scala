package com.keepit.controllers.assets

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.{Success, Failure}

import org.joda.time.Weeks

import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.model.ObjectMetadata
import com.google.inject.Inject
import com.google.inject.Singleton
import com.keepit.common.controller.{WebsiteController, ActionAuthenticator}
import com.keepit.common.db.ExternalId
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.{HealthcheckPlugin, Healthcheck, HealthcheckError}
import com.keepit.common.social.SocialNetworks
import com.keepit.common.store.S3ImageConfig
import com.keepit.common.time._
import com.keepit.model._

import play.api.libs.ws.WS
import play.api.mvc.Action

@Singleton
class UserPictureController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  db: Database,
  userRepo: UserRepo,
  userValueRepo: UserValueRepo,
  config: S3ImageConfig,
  s3Client: AmazonS3,
  suiRepo: SocialUserInfoRepo,
  healthcheckPlugin: HealthcheckPlugin,
  clock: Clock)
  extends WebsiteController(actionAuthenticator) {

  private val UserPictureLastUpdatedKey = "user_picture_last_updated"
  private val ExpirationTime = Weeks.ONE

  def get(width: Int, userExternalId: ExternalId[User]) = Action { request =>
    (for {
      user <- db.readOnly { implicit s => userRepo.getOpt(userExternalId) }
      sui <- db.readOnly { implicit s => suiRepo.getByUser(user.id.get).headOption }
    } yield {
      db.readOnly { implicit s => userValueRepo.getValue(user.id.get, UserPictureLastUpdatedKey) }.map { s =>
        parseStandardTime(s).isAfter(clock.now().minus(ExpirationTime))
      } match {
        case None =>
          // Grandfather old users without a picture uploaded
          asyncUpdatePicture(sui, userExternalId)
          Redirect(avatarUrlFromSocialNetwork(sui, width))
        case Some(upToDate) =>
          if (!upToDate) asyncUpdatePicture(sui, userExternalId)
          Redirect(config.avatarUrlByExternalId(width, userExternalId))
      }
    }) getOrElse NotFound("Cannot find user!")
  }

  private def avatarUrlFromSocialNetwork(sui: SocialUserInfo, size: Int, withScheme: Boolean = false): String = {
    if (sui.networkType != SocialNetworks.FACEBOOK) {
      throw new UnsupportedOperationException("We only support facebook right now")
    }
    s"${if (withScheme) "https:" else ""}//graph.facebook.com/${sui.socialId.id}/picture?width=$size&height=$size"
  }

  private def asyncUpdatePicture(socialUserInfo: SocialUserInfo, userExternalId: ExternalId[User]) {
    Future.sequence(for {
      size <- S3ImageConfig.ImageSizes
      userId <- socialUserInfo.userId
    } yield {
      val originalImageUrl = avatarUrlFromSocialNetwork(socialUserInfo, size, true)
      WS.url(originalImageUrl).get().map { response =>
        val key = config.keyByExternalId(size, userExternalId)
        log.info(s"Uploading picture $originalImageUrl to S3 key $key")
        val om = new ObjectMetadata()
        om.setContentType("image/jpeg")
        s3Client.putObject(config.bucketName, key, response.getAHCResponse.getResponseBodyAsStream, om)
      }
    }) onComplete {
      case Success(_) =>
        db.readWrite { implicit s =>
          userValueRepo.setValue(
            socialUserInfo.userId.get, UserPictureLastUpdatedKey,
            clock.now().toStandardTimeString)
        }
      case Failure(e) =>
        healthcheckPlugin.addError(HealthcheckError(
        error = Some(e),
        callType = Healthcheck.INTERNAL,
        errorMessage = Some("Failed to upload picture to S3")
      ))
    }
  }
}
