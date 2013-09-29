package com.keepit.controllers.assets

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.util.Try

import com.google.inject.Inject
import com.keepit.common.controller.{WebsiteController, ActionAuthenticator}
import com.keepit.common.db.ExternalId
import com.keepit.common.db.slick.Database
import com.keepit.common.store.S3ImageStore
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

  def get(size: Int, id: ExternalId[User]) = Action { request =>
    db.readOnly { implicit s => userRepo.getOpt(id) } collect {
      case user if Set(UserStates.ACTIVE, UserStates.PENDING) contains user.state =>
        Async {
          imageStore.getPictureUrl(size, user) map (Redirect(_))
        }
    } getOrElse {
      NotFound("Cannot find user!")
    }
  }

  def update() = Action { request =>
    Async {
      Future.sequence(for {
        user <- db.readOnly { implicit s => userRepo.allExcluding(UserStates.INACTIVE) }
      } yield {
        val socialUser = db.readOnly { implicit s => suiRepo.getByUser(user.id.get) }.head
        imageStore.updatePicture(socialUser, user.externalId).map(_ => socialUser.socialId)
      }).map { results =>
        Ok(results.mkString(","))
      }
    }
  }
}
