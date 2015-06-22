package com.keepit.controllers.assets

import com.keepit.social.BasicUser
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.util.Try

import com.google.inject.Inject
import com.keepit.common.controller.{ ShoeboxServiceController, UserActions, UserActionsHelper }
import com.keepit.common.db.{ Id, ExternalId }
import com.keepit.common.db.slick.Database
import com.keepit.common.store.{ S3ImageConfig, S3UserPictureConfig, S3ImageStore }
import com.keepit.model._
import scala.concurrent.{ Await, Future }

import play.api.mvc.{ Result, Action }
import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.model.{ ListObjectsRequest, CopyObjectRequest }
import com.keepit.common.akka.SafeFuture
import scala.concurrent.duration.Duration
import java.util.concurrent.TimeUnit
import com.keepit.common.core._

class UserPictureController @Inject() (
  val userActionsHelper: UserActionsHelper,
  db: Database,
  suiRepo: SocialUserInfoRepo,
  userRepo: UserRepo,
  imageStore: S3ImageStore,
  val config: S3ImageConfig)
    extends UserActions with ShoeboxServiceController {

  def getPic(size: String, id: ExternalId[User], picName: String) = Action.async { request =>
    imageUrl(Try(size.toInt).getOrElse(200), id)
  }

  def get(size: Int, id: ExternalId[User]) = Action.async { request =>
    imageUrl(Try(size.toInt).getOrElse(200), id)
  }

  private def imageUrl(size: Int, id: ExternalId[User]): Future[Result] = {
    // This is a black box function. It should be replaced when user images work a bit better.
    // Input is a userExtId and requested size. Output is the image to show. The subtlety here is that social images
    // can take a second to finish uploading, and we'd prefer to show someone the image they *just* authed with, rather
    // than a placeholder image. So we wait.
    // Normally, this function is not called; instead the CDN is hit directly because all the info needed is known.
    // However, signup confirmation page and iOS use this.
    val userOpt = db.readOnlyReplica { implicit s =>
      userRepo.getOpt(id)
    }

    userOpt.collect {
      case user if Set(UserStates.ACTIVE, UserStates.PENDING, UserStates.INCOMPLETE_SIGNUP).contains(user.state) =>
        val optSize = Some(size)
        Try(user.pictureName.map { pictureName =>
          imageStore.getPictureUrl(optSize, user, pictureName).map(r => Try(r).toOption)
        }.getOrElse {
          imageStore.getPictureUrl(optSize, user, "0").map(r => Try(r).toOption)
        }).getOrElse(Future.successful(Some("/0.jpg")))
    }.getOrElse(Future.successful(None)).map {
      case Some(imgUrl) if userOpt.isDefined && (imgUrl.endsWith("/0.jpg") || imgUrl.endsWith("ghost.200.png")) =>
        // We may be redirecting to a default image instead.
        val url = if (BasicUser.useDefaultImageForUser(userOpt.get)) {
          val img = BasicUser.defaultImageForUserId(userOpt.get.id.get)
          s"https://djty7jcqog9qu.cloudfront.net$img" // hard coded to remove dependency on existing (bad) methods
        } else {
          imgUrl
        }
        Redirect(url)
      case Some(imgUrl) =>
        Redirect(imgUrl)
      case None => NotFound
    }
  }

  def hackyRedirectForiOSv3(file: String) = Action { request =>
    Redirect(s"https://djty7jcqog9qu.cloudfront.net/default-pic/$file")
  }

  def update() = UserAction.async { request =>
    if (request.experiments.contains(ExperimentType.ADMIN)) {
      Future.sequence(for {
        user <- db.readOnlyReplica { implicit s => userRepo.allExcluding(UserStates.INACTIVE) }
      } yield {
        val socialUser = db.readOnlyReplica { implicit s => suiRepo.getByUser(user.id.get) }.head
        imageStore.uploadPictureFromSocialNetwork(socialUser, user.externalId, setDefault = false).map(_ => socialUser.socialId)
      }).map { results =>
        Ok(results.mkString(","))
      }
    } else {
      resolve(Forbidden)
    }
  }

}
