package com.keepit.controllers.assets

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.util.Try

import com.google.inject.Inject
import com.keepit.common.controller.{WebsiteController, ActionAuthenticator}
import com.keepit.common.db.{Id, ExternalId}
import com.keepit.common.db.slick.Database
import com.keepit.common.store.{S3ImageConfig, S3UserPictureConfig, S3ImageStore}
import com.keepit.model._
import scala.concurrent.{Await, Future}

import play.api.mvc.Action
import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.model.{ListObjectsRequest, CopyObjectRequest}
import com.keepit.common.akka.SafeFuture
import scala.concurrent.duration.Duration
import java.util.concurrent.TimeUnit

class UserPictureController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  db: Database,
  suiRepo: SocialUserInfoRepo,
  userRepo: UserRepo,
  imageStore: S3ImageStore,
  val config: S3ImageConfig,
  s3Client: AmazonS3)
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
          imageStore.uploadPictureFromSocialNetwork(socialUser, user.externalId, setDefault = false).map(_ => socialUser.socialId)
        }).map { results =>
          Ok(results.mkString(","))
        }
      }
    } else {
      Forbidden
    }
  }

//  def metaUpdate() = Action {
//    import scala.collection.JavaConversions._
//    def updateKey(key: String) = {
//      println(s"${config.bucketName} $key")
//      val oldMeta = s3Client.getObjectMetadata(config.bucketName, key)
//      val length = if (key.endsWith("0.jpg")) "3600" else "86400"
//      oldMeta.setCacheControl(s"public, max-age=$length")
//      val p = new CopyObjectRequest(config.bucketName, key, config.bucketName, key).withNewObjectMetadata(oldMeta)
//      s3Client.copyObject(p)
//      key + " " + length
//    }
//    val res = "123456789abcdef".map { c =>
//      s3Client.listObjects(config.bucketName, "users/" + c).getObjectSummaries().map { v =>
//        val key = v.getKey
//        updateKey(key)
//      }.mkString("\n")
//    }.mkString("\n\n")
//    Ok(res)
//  }

  def metaUpdate() = Action {
    import scala.collection.JavaConversions._
    def updateKey(key: String) = {
      println(s"${config.bucketName} $key")
      val oldMeta = s3Client.getObjectMetadata(config.bucketName, key)
      val length = "1800"
      oldMeta.setCacheControl(s"public, max-age=$length")
      val p = new CopyObjectRequest(config.bucketName, key, config.bucketName, key).withNewObjectMetadata(oldMeta)
      s3Client.copyObject(p)
      key
    }
    val res = "0123456789abcdef".map { first =>
      "0123456789abcdef".map { second =>
        val summaries = s3Client.listObjects(config.bucketName, "screenshot/" + first + second).getObjectSummaries()
        summaries.grouped(20).map { groups =>
          val combinedResult = Future.sequence(groups.map { v =>
            Future {
              val key = v.getKey
              updateKey(key)
            }
          })
          Await.result(combinedResult, Duration(1, TimeUnit.MINUTES)).flatten.mkString("\n")
        }.toList.mkString("\n")
      }
    }.flatten.mkString("\n\n")
    Ok(res)
  }

}
