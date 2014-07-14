package com.keepit.controllers.assets

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.util.Try

import com.google.inject.Inject
import com.keepit.common.controller.{ ShoeboxServiceController, WebsiteController, ActionAuthenticator }
import com.keepit.common.db.{ Id, ExternalId }
import com.keepit.common.db.slick.Database
import com.keepit.common.store.{ S3ImageConfig, S3UserPictureConfig, S3ImageStore }
import com.keepit.model._
import scala.concurrent.{ Await, Future }

import play.api.mvc.Action
import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.model.{ ListObjectsRequest, CopyObjectRequest }
import com.keepit.common.akka.SafeFuture
import scala.concurrent.duration.Duration
import java.util.concurrent.TimeUnit

class UserPictureController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  db: Database,
  suiRepo: SocialUserInfoRepo,
  userRepo: UserRepo,
  imageStore: S3ImageStore,
  val config: S3ImageConfig)
    extends WebsiteController(actionAuthenticator) with ShoeboxServiceController {

  def getPic(size: String, id: ExternalId[User], picName: String) = Action.async { request =>
    val trimmedName = if (picName.endsWith(".jpg")) picName.dropRight(4) else picName
    db.readOnlyReplica { implicit s => userRepo.getOpt(id) } collect {
      case user if Set(UserStates.ACTIVE, UserStates.PENDING, UserStates.INCOMPLETE_SIGNUP) contains user.state =>
        val optSize = if (size == "original") None else Try(size.toInt).toOption
        imageStore.getPictureUrl(optSize, user, trimmedName) map (Redirect(_))
    } getOrElse {
      resolve(Redirect(S3UserPictureConfig.defaultImage))
    }
  }

  def get(size: Int, id: ExternalId[User]) = Action.async { request =>
    db.readOnlyReplica { implicit s => userRepo.getOpt(id) } collect {
      case user if Set(UserStates.ACTIVE, UserStates.PENDING, UserStates.INCOMPLETE_SIGNUP) contains user.state =>
        val optSize = Some(size)
        user.pictureName.map { pictureName =>
          imageStore.getPictureUrl(optSize, user, pictureName) map (Redirect(_))
        } getOrElse {
          imageStore.getPictureUrl(optSize, user, "0") map (Redirect(_))
        }
    } getOrElse {
      resolve(Redirect(S3UserPictureConfig.defaultImage))
    }
  }

  def update() = HtmlAction.authenticatedAsync { request =>
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
