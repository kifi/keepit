package com.keepit.common.store

import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.model.PutObjectResult

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.cache.TransactionalCaching

import com.keepit.common.controller.{ UserActionsHelper }
import com.keepit.common.db.{ Id, ExternalId }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.{ AirbrakeNotifier, AirbrakeError }
import com.keepit.common.logging.Logging
import com.keepit.common.net.{ NonOKResponseException, URI }
import com.keepit.common.time._
import com.keepit.eliza.ElizaServiceClient
import com.keepit.model._
import com.keepit.social.{ SocialNetworkType, BasicUser, SocialNetworks }
import com.ning.http.client.providers.netty.NettyResponse

import org.apache.commons.lang3.RandomStringUtils
import org.joda.time.Weeks

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.Json
import play.api.libs.ws.WS

import java.awt.image.BufferedImage
import java.io.{ ByteArrayInputStream, ByteArrayOutputStream, File, InputStream }
import javax.imageio.ImageIO

import scala.concurrent.{ Future, Promise }
import scala.util.{ Failure, Success, Try }
import play.api.Play.current

@ImplementedBy(classOf[S3ImageStoreImpl])
trait S3ImageStore extends S3ExternalIdImageStore {

  def getPictureUrl(width: Int, userId: Id[User]): Future[String]
  def getPictureUrl(width: Option[Int], user: User, picName: String): Future[String]
  def uploadRemotePicture(userId: Id[User], externalId: ExternalId[User], pictureSource: UserPictureSource, pictureName: Option[String], setDefault: Boolean)(getPictureUrl: Option[ImageSize] => Option[String]): Future[Seq[(String, Try[PutObjectResult])]]
  def forceUpdateSocialPictures(userId: Id[User]): Unit

  // Returns (token, urlOfTempImage)
  def uploadTemporaryPicture(file: File): Try[(String, String)]

  // Returns Some(urlOfUserPicture) or None
  def copyTempFileToUserPic(userId: Id[User], userExtId: ExternalId[User], token: String, cropAttributes: Option[ImageCropAttributes]): Option[String]

}

@Singleton
class S3ImageStoreImpl @Inject() (
    val userActionsHelper: UserActionsHelper,
    db: Database,
    userValueRepo: UserValueRepo,
    override val s3Client: AmazonS3,
    suiRepo: SocialUserInfoRepo,
    userRepo: UserRepo,
    airbrake: AirbrakeNotifier,
    clock: Clock,
    userPictureRepo: UserPictureRepo,
    userImageUrlCache: UserImageUrlCache,
    eliza: ElizaServiceClient,
    imageUtils: ImageUtils,
    val config: S3ImageConfig) extends S3ImageStore with S3Helper with Logging {

  private val ExpirationTime = Weeks.ONE

  def getPictureUrl(width: Int, userId: Id[User]): Future[String] = {
    val user = db.readOnlyMaster { implicit session => userRepo.get(userId) }
    val imageName = user.pictureName.getOrElse("0")
    implicit val txn = TransactionalCaching.Implicits.directCacheAccess
    userImageUrlCache.getOrElseFuture(UserImageUrlCacheKey(userId, width, imageName)) {
      getPictureUrl(Some(width), user, imageName)
    }
  }

  def getPictureUrl(width: Option[Int], user: User, pictureName: String): Future[String] = {
    if (config.isLocal) {
      val sui = db.readOnlyReplica { implicit s => suiRepo.getByUser(user.id.get).head }
      val preferredSize = width.map(w => ImageSize(w, w))
      val pictureUrl = sui.getPictureUrl(preferredSize) getOrElse S3UserPictureConfig.defaultImage
      Future.successful(pictureUrl)
    } else {
      user.userPictureId match {
        case None =>
          // No picture uploaded, wait for it
          val sui = db.readOnlyMaster { implicit s =>
            val suis = suiRepo.getByUser(user.id.get)
            // If user has no picture, this is the preference order for social networks:
            val suiOpt = suis.find(_.networkType == SocialNetworks.FACEBOOK).find(_.networkType == SocialNetworks.TWITTER).orElse(suis.find(_.networkType == SocialNetworks.LINKEDIN))
            suiOpt.getOrElse {
              if (suis.isEmpty) throw new Exception(s"user has no social users attached: $user")
              suis.head
            }
          }
          if (sui.networkType != SocialNetworks.FORTYTWO) {
            uploadRemotePicture(user.id.get, user.externalId, UserPictureSource(sui.networkType), None, setDefault = true)(sui.getPictureUrl).map {
              case res =>
                avatarUrlByExternalId(width, user.externalId, res.head._1)
            }
          } else {
            uploadRemotePicture(user.id.get, user.externalId, UserPictureSource(sui.networkType), None, setDefault = false)(sui.getPictureUrl)
            val preferredSize = width.map(w => ImageSize(w, w))
            val pictureUrl = sui.getPictureUrl(preferredSize) getOrElse S3UserPictureConfig.defaultImage
            Future.successful(pictureUrl)
          }
        case Some(userPicId) =>
          // We have an image so serve that one, even if it might be outdated
          if (user.pictureName.isEmpty || pictureName == user.pictureName.get) {
            // Only update the primary picture
            db.readOnlyReplica { implicit session =>
              val pic = userPictureRepo.get(userPicId)
              val upToDate = pic.origin.name == "user_upload" || pic.updatedAt.isAfter(clock.now().minus(ExpirationTime))
              if (!upToDate) {
                suiRepo.getByUser(user.id.get).filter(_.networkType.name == pic.origin.name).map { sui =>
                  // todo(Andrew): For now, *replace* social images. Soon: detect if it has changed, and create new.
                  uploadRemotePicture(user.id.get, user.externalId, UserPictureSource(sui.networkType), Some(pictureName), setDefault = false)(sui.getPictureUrl)
                }
              }
            }
          }

          Promise.successful(avatarUrlByExternalId(width, user.externalId, pictureName)).future
      }
    }
  }

  private def sizeName(size: Option[ImageSize]): String = size match {
    case None => S3UserPictureConfig.OriginalImageSize
    case Some(imageSize) if imageSize.width != imageSize.height => s"${imageSize.width}x${imageSize.height}"
    case Some(squareImageSize) => squareImageSize.width.toString
  }

  def uploadRemotePicture(userId: Id[User], externalId: ExternalId[User], pictureSource: UserPictureSource, pictureName: Option[String], setDefault: Boolean)(getPictureUrl: Option[ImageSize] => Option[String]): Future[Seq[(String, Try[PutObjectResult])]] = {
    if (config.isLocal) {
      Promise.successful(Seq()).future
    } else {
      val actualPictureName = pictureName getOrElse UserPicture.generateNewFilename
      // todo: Grab largest image social network allows, do resizing ourselves (the same way we do for uploaded images)
      Future.sequence((S3UserPictureConfig.sizes.map(Some(_)) :+ None).flatMap { size =>
        getPictureUrl(size).map { originalImageUrl =>
          val future = WS.url(originalImageUrl).withRequestTimeout(120000).get().map {
            case response if response.status == 200 => Some {
              val key = keyByExternalId(sizeName(size), externalId, actualPictureName)
              val putObj = uploadToS3(key, response.underlying[NettyResponse].getResponseBodyAsStream, label = originalImageUrl)
              if (putObj.isSuccess) {
                updateUserPictureRecord(userId, actualPictureName, pictureSource, setDefault, None)
              }
              (actualPictureName, putObj)
            }
            case nonOkResponse => None //ignore
          }

          future onFailure { case e => airbrake.notify(s"Failed to upload picture $pictureName - $externalId from $pictureSource to S3", e) }
          future
        }
      }).map(_.flatten)
    }
  }

  def forceUpdateSocialPictures(userId: Id[User]): Unit = {
    val (sui, user, picName) = db.readOnlyMaster { implicit s =>
      val user = userRepo.get(userId)
      val suis = suiRepo.getByUser(user.id.get)
      // If user has no picture, this is the preference order for social networks:
      val sui = suis.find(_.networkType == SocialNetworks.FACEBOOK).orElse(suis.find(_.networkType == SocialNetworks.LINKEDIN)).getOrElse(suis.head)
      val picName = user.userPictureId.map(pid => userPictureRepo.get(pid).name)
      (sui, user, picName)
    }
    uploadRemotePicture(user.id.get, user.externalId, UserPictureSource(sui.networkType), picName, setDefault = true)(sui.getPictureUrl)
  }

  private def uploadToS3(key: String, is: InputStream, contentLength: Int = 0, label: String = "") =
    streamUpload(config.bucketName, key, is, "public, max-age=3600", contentLength, label)

  private def updateUserPictureRecord(userId: Id[User], pictureName: String, source: UserPictureSource, setDefault: Boolean, cropAttributes: Option[ImageCropAttributes]) = {
    val jsonCropAttributes = cropAttributes.map { c =>
      Json.obj("w" -> c.w, "h" -> c.h, "x" -> c.x, "y" -> c.y, "s" -> c.s)
    }
    db.readWrite { implicit s =>
      val user = userRepo.get(userId)
      if (user.userPictureId.isEmpty) {
        // User has no picture set
        val userPicture = userPictureRepo.save(UserPicture(userId = userId, name = pictureName, origin = source, attributes = jsonCropAttributes))
        Some(userRepo.save(user.copy(pictureName = Some(pictureName), userPictureId = userPicture.id)))
      } else if (user.pictureName == Some(pictureName)) {
        val prevPic = userPictureRepo.get(user.userPictureId.get)
        userPictureRepo.save(prevPic) // touch updatedAt
        None
      } else {
        val userPicRecord = userPictureRepo.save(
          userPictureRepo.getByName(userId, pictureName)
            .getOrElse(UserPicture(userId = userId, name = pictureName, origin = source, attributes = jsonCropAttributes))
            .withState(UserPictureStates.ACTIVE)
        )
        if (setDefault) {
          Some(userRepo.save(user.copy(userPictureId = userPicRecord.id, pictureName = Some(pictureName))))
        } else {
          None
        }
      }
    } map { user =>
      eliza.sendToUser(userId, Json.arr("new_pic", BasicUser.fromUser(user).pictureName))
    }
  }

  def readImage(file: File): BufferedImage = {
    imageUtils.forceRGB(ImageIO.read(file))
  }
  def readImage(is: InputStream): BufferedImage = {
    imageUtils.forceRGB(ImageIO.read(is))
  }

  def uploadTemporaryPicture(file: File): Try[(String, String)] = Try {

    val bufImage = readImage(file)

    val os = new ByteArrayOutputStream()
    ImageIO.write(bufImage, "jpeg", os)
    val is = new ByteArrayInputStream(os.toByteArray())

    val token = RandomStringUtils.randomAlphanumeric(10)
    val key = tempPath(token)
    uploadToS3(key, is, os.size(), "temporary user upload")

    (token, s"${config.cdnBase}/$key")
  }

  def copyTempFileToUserPic(userId: Id[User], userExtId: ExternalId[User], token: String, cropAttributes: Option[ImageCropAttributes]): Option[String] = {
    val newFilename = UserPicture.generateNewFilename
    getImage(token) match {
      case Success(bufferedImage) =>
        uploadAllUserImages(userId, userExtId, newFilename, bufferedImage, cropAttributes)
      case Failure(ex) =>
        airbrake.notify(AirbrakeError(exception = ex, message = Some(s"Failed to fetch picture $newFilename from S3")))
        None
    }
  }

  private def uploadAllUserImages(userId: Id[User], userExtId: ExternalId[User], newFilename: String, bufferedImage: BufferedImage, cropAttributes: Option[ImageCropAttributes]) = {
    val (origContentLength, origInputStream) = imageUtils.bufferedImageToInputStream(bufferedImage)
    uploadUserImage(userExtId, newFilename, "original", origInputStream, origContentLength) match {
      case Success(res) =>
        val resizedImageResults = cropResizeAndUpload(userExtId, newFilename, bufferedImage, cropAttributes)
        resizedImageResults.find(_.isDefined).map { hadASuccess =>
          updateUserPictureRecord(userId, newFilename, UserPictureSource.USER_UPLOAD, true, cropAttributes)
          hadASuccess
        }.flatten.map { success =>
          avatarUrlByExternalId(Some(S3UserPictureConfig.ImageSizes.last), userExtId, newFilename)
        }
      case Failure(ex) =>
        airbrake.notify(AirbrakeError(exception = ex, message = Some(s"Failed to upload original picture $newFilename ($origContentLength) from S3")))
        None
    }
  }
  private def getImage(token: String) = Try {
    val obj = s3Client.getObject(config.bucketName, tempPath(token))
    readImage(obj.getObjectContent)
  }
  private def uploadUserImage(userExtId: ExternalId[User], filename: String, imageSizeName: String, is: ByteArrayInputStream, contentLength: Int) = Try {
    val key = keyByExternalId(imageSizeName, userExtId, filename)
    uploadToS3(key, is, contentLength, "uploaded pic to user location")
    key
  }
  private def cropImageOrFallback(newFilename: String, bufferedImage: BufferedImage, cropAttributes: Option[ImageCropAttributes]) = {
    cropAttributes.map { attr =>
      Try { imageUtils.cropSquareImage(bufferedImage, attr.x, attr.y, attr.s) } match {
        case Success(cropped) => Some(cropped)
        case Failure(ex) =>
          airbrake.notify(AirbrakeError(exception = ex, message = Some(s"Failed to crop picture $newFilename")))
          None
      }
    }.flatten.getOrElse(bufferedImage)
  }
  private def cropResizeAndUpload(userExtId: ExternalId[User], newFilename: String, bufferedImage: BufferedImage, cropAttributes: Option[ImageCropAttributes]) = {
    val image = cropImageOrFallback(newFilename, bufferedImage, cropAttributes)
    S3UserPictureConfig.sizes.map { size =>
      Try(imageUtils.resizeImageMakeSquare(image, size)).map {
        case (contentLength, is) =>
          uploadUserImage(userExtId, newFilename, size.width.toString, is, contentLength)
      }.flatten match {
        case Success(res) => Some(res)
        case Failure(ex) =>
          airbrake.notify(AirbrakeError(exception = ex, message = Some(s"Failed to resize/upload ${size.width} picture $newFilename to S3")))
          None
      }
    }
  }

}
