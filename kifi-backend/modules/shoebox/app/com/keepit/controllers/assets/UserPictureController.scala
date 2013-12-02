package com.keepit.controllers.assets

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.util.Try

import com.google.inject.Inject
import com.keepit.common.controller.{WebsiteController, ActionAuthenticator}
import com.keepit.common.db.{Id, ExternalId}
import com.keepit.common.db.slick.Database
import com.keepit.common.store.{S3UserPictureConfig, S3ImageStore}
import com.keepit.model._
import scala.concurrent.Future

import play.api.mvc.Action

class UserPictureController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  db: Database,
  suiRepo: SocialUserInfoRepo,
  userRepo: UserRepo,
  imageStore: S3ImageStore)
  extends WebsiteController(actionAuthenticator) {

  def getPic(size: String, id: ExternalId[User], picName: String) = Action { request =>
    val trimmedName = if (picName.endsWith(".jpg")) picName.dropRight(4) else picName
    db.readOnly { implicit s => userRepo.getOpt(id) } collect {
      case user if Set(UserStates.ACTIVE, UserStates.PENDING, UserStates.INCOMPLETE_SIGNUP) contains user.state =>
        Async {
          val optSize = if (size == "original") None else Try(size.toInt).toOption
          imageStore.getPictureUrl(optSize, user, trimmedName) map (Redirect(_))
        }
    } getOrElse {
      Redirect(S3UserPictureConfig.defaultImage)
    }
  }

  def get(size: Int, id: ExternalId[User]) = Action { request =>
    db.readOnly { implicit s => userRepo.getOpt(id) } collect {
      case user if Set(UserStates.ACTIVE, UserStates.PENDING, UserStates.INCOMPLETE_SIGNUP) contains user.state =>
        Async {
          val optSize = Some(size)
          user.pictureName.map { pictureName =>
            imageStore.getPictureUrl(optSize, user, pictureName) map (Redirect(_))
          } getOrElse {
            imageStore.getPictureUrl(optSize, user, "0") map (Redirect(_))
          }
        }
    } getOrElse {
      Redirect(S3UserPictureConfig.defaultImage)
    }
  }

  def update() = AuthenticatedHtmlAction { request =>
    if (request.experiments.contains(ExperimentType.ADMIN)) {
      Async {
        Future.sequence(for {
          user <- db.readOnly { implicit s => userRepo.allExcluding(UserStates.INACTIVE) }
        } yield {
          val socialUser = db.readOnly { implicit s => suiRepo.getByUser(user.id.get) }.head
          imageStore.uploadPictureFromSocialNetwork(socialUser, user.externalId).map(_ => socialUser.socialId)
        }).map { results =>
          Ok(results.mkString(","))
        }
      }
    } else {
      Forbidden
    }
  }
}
