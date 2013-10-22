package com.keepit.common.store

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.util.{Try, Success, Failure}
import org.joda.time.Weeks
import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.model.{PutObjectResult, ObjectMetadata}
import com.google.inject.{ImplementedBy, Inject, Singleton}
import com.keepit.common.controller.ActionAuthenticator
import com.keepit.common.db.ExternalId
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.{HealthcheckPlugin, Healthcheck}
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.common.time.parseStandardTime
import com.keepit.model._
import play.api.libs.ws.WS
import com.keepit.social.SocialNetworks
import com.keepit.common.net.URI
import scala.util.Failure
import scala.Some
import com.keepit.common.healthcheck.HealthcheckError
import scala.util.Success

object S3UserPictureConfig {
  val ImageSizes = Seq(100, 200)
  val OriginalImageSize = "original"
}

@ImplementedBy(classOf[S3ImageStoreImpl])
trait S3ImageStore {
  def config: S3ImageConfig
  def getPictureUrl(width: Int, user: User): Future[String]
  def getPictureUrl(width: Option[Int], user: User, picVersion: String): Future[String]
  def uploadPictureFromSocialNetwork(sui: SocialUserInfo, externalId: ExternalId[User], pictureName: String): Future[Seq[(String, Try[PutObjectResult])]]
  def uploadPictureFromSocialNetwork(sui: SocialUserInfo, externalId: ExternalId[User]): Future[Seq[(String, Try[PutObjectResult])]]


  def avatarUrlByExternalId(w: Option[Int], userId: ExternalId[User], picVersion: String, protocolDefault: Option[String] = None): String = {
    val size = S3UserPictureConfig.ImageSizes.find(size => w.exists(size >= _)).map(_.toString).getOrElse(S3UserPictureConfig.OriginalImageSize)
    val uri = URI.parse(s"${config.cdnBase}/${keyByExternalId(size, userId, picVersion)}").get
    URI(uri.scheme orElse protocolDefault, uri.userInfo, uri.host, uri.port, uri.path, uri.query, uri.fragment).toString
  }

  def keyByExternalId(size: String, userId: ExternalId[User], picVersion: String): String =
    s"users/$userId/pics/$size/$picVersion.jpg"
}

@Singleton
class S3ImageStoreImpl @Inject() (
    actionAuthenticator: ActionAuthenticator,
    db: Database,
    userValueRepo: UserValueRepo,
    s3Client: AmazonS3,
    suiRepo: SocialUserInfoRepo,
    userRepo: UserRepo,
    healthcheckPlugin: HealthcheckPlugin,
    clock: Clock,
    userPictureRepo: UserPictureRepo,
    val config: S3ImageConfig
  ) extends S3ImageStore with Logging {

  private val ExpirationTime = Weeks.ONE

  def getPictureUrl(width: Int, user: User): Future[String] = getPictureUrl(Some(width), user, "0.jpg") // todo: Change to default

  def getPictureUrl(width: Option[Int], user: User, pictureName: String): Future[String] = {
    if (config.isLocal) {
      val sui = db.readOnly { implicit s => suiRepo.getByUser(user.id.get).head }
      Promise.successful(avatarUrlFromSocialNetwork(sui, width.map(_.toString).getOrElse("original"))).future
    } else {
      user.userPictureId match {
        case None =>
          // No picture uploaded, wait for it
          val sui = db.readOnly { implicit s =>
            val suis = suiRepo.getByUser(user.id.get)
            // If user has no picture, this is the preference order for social networks:
            suis.find(_.networkType == SocialNetworks.FACEBOOK).orElse(suis.find(_.networkType == SocialNetworks.LINKEDIN)).getOrElse(suis.head)
          }
          if (sui.networkType != SocialNetworks.FORTYTWO) {
            uploadPictureFromSocialNetwork(sui, user.externalId).map { case res =>
              avatarUrlByExternalId(width, user.externalId, res.head._1)
            }
          } else {
            Promise.successful(avatarUrlFromSocialNetwork(sui, width.map(_.toString).getOrElse("original"))).future
          }
        case Some(userPicId) =>
          // We have an image so serve that one, even if it might be outdated
          if (user.pictureName.isEmpty || pictureName == user.pictureName.get) {
            // Only update the primary picture
            db.readOnly { implicit session =>
              val pic = userPictureRepo.get(userPicId)
              val upToDate = pic.origin.name == "user_upload" || pic.updatedAt.isAfter(clock.now().minus(ExpirationTime))
              if (!upToDate) {
                suiRepo.getByUser(user.id.get).filter(_.networkType.name == pic.origin.name).map { sui =>
                  // For now, *replace* social images. Soon: detect if it has changed, and create new.
                  uploadPictureFromSocialNetwork(sui, user.externalId, pictureName)
                }
              }
            }
          }

          Promise.successful(avatarUrlByExternalId(width, user.externalId, pictureName)).future
      }
    }
  }

  private def avatarUrlFromSocialNetwork(sui: SocialUserInfo, size: String): String = {
    val numericSize = (if (size == "original") None else Try(size.toInt).toOption) getOrElse 1000
    sui.getPictureUrl(numericSize, numericSize).getOrElse(
      "http://s.c.lnkd.licdn.com/scds/common/u/images/themes/katy/ghosts/person/ghost_person_200x200_v1.png")
  }

  def uploadPictureFromSocialNetwork(sui: SocialUserInfo, externalId: ExternalId[User]): Future[Seq[(String, Try[PutObjectResult])]] =
    uploadPictureFromSocialNetwork(sui, externalId, userPictureRepo.generateNewFilename)

  def uploadPictureFromSocialNetwork(sui: SocialUserInfo, externalId: ExternalId[User], pictureName: String): Future[Seq[(String, Try[PutObjectResult])]] = {
    if (config.isLocal) {
      Promise.successful(Seq()).future
    } else {
      val future = Future.sequence(for {
        sizeName <- S3UserPictureConfig.ImageSizes.map(_.toString) :+ "original"
        userId <- sui.userId
      } yield {
        val px = if (sizeName == "original") "1000" else sizeName
        val originalImageUrl = avatarUrlFromSocialNetwork(sui, px)
        WS.url(originalImageUrl).get().map { response =>
          val key = keyByExternalId(sizeName.toString, externalId, pictureName)
          log.info(s"Uploading picture $originalImageUrl to S3 key $key")
          val om = new ObjectMetadata()
          om.setContentType("image/jpeg")
          (pictureName, Try(s3Client.putObject(config.bucketName, key, response.getAHCResponse.getResponseBodyAsStream, om)))
        }
      })
      future onComplete {
        case Success(_) =>
          db.readWrite { implicit s =>
            val user = userRepo.get(sui.userId.get)
            if (user.userPictureId.isEmpty || user.pictureName.isEmpty || user.pictureName.get != pictureName) {
              val userPicture = userPictureRepo.save(UserPicture(userId = sui.userId.get, name = pictureName, origin = UserPictureSource(sui.networkType.name)))

              user.userPictureId match {
                case Some(prevId) => // User has a picture set
                  val prevPic = userPictureRepo.get(prevId)
                  if (prevPic.origin.name == sui.networkType.name) { // User currently is using this social network's picture
                    userRepo.save(user.copy(pictureName = Some(pictureName), userPictureId = userPicture.id))
                    userPictureRepo.save(userPictureRepo.get(prevId).withState(UserPictureStates.INACTIVE))
                  }
                case None => // User has no picture set
                  userRepo.save(user.copy(pictureName = Some(pictureName), userPictureId = userPicture.id))
              }
            }
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
