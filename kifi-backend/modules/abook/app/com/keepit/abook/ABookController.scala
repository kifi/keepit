package com.keepit.abook

import com.google.inject.Inject
import com.keepit.common.db.slick.Database
import com.keepit.common.controller.{ABookServiceController, ActionAuthenticator}
import com.keepit.model.{ABookOriginType, ABook, User}
import com.keepit.common.db.Id
import play.api.mvc.Action
import com.keepit.abook.store.{ABookRawInfo, ABookRawInfoStore}
import play.api.libs.json.{JsValue, Json}

class ABookController @Inject() (
  actionAuthenticator:ActionAuthenticator,
  db:Database,
  s3:ABookRawInfoStore,
  abookRepo:ABookRepo,
  contactInfoRepo:ContactInfoRepo
) extends ABookServiceController {

  // for testing only
  def upload(userId:Id[User], origin:ABookOriginType) = Action(parse.json(maxLength = 1024 * 5000)) { request =>
    val abookRawInfoRes = Json.fromJson[ABookRawInfo](request.body)
    val abookRawInfo = abookRawInfoRes.getOrElse(throw new Exception(s"Cannot parse ${request.body}"))

    val s3Key = s"${userId}_${abookRawInfo.origin}"
    s3 += (s3Key -> abookRawInfo) // TODO: put on queue
    log.info(s"[upload] s3Key=$s3Key rawInfo=$abookRawInfo}")

    // TODO: cache (if needed)

    val abookRepoEntry = db.readWrite { implicit session =>
      val abook = ABook(userId = userId, origin = abookRawInfo.origin, rawInfoLoc = Some(s3Key))
      val oldVal = abookRepo.findByUserIdAndOriginOpt(userId, abookRawInfo.origin)
      val entry = oldVal match {
        case Some(e) => {
          log.info(s"[upload] old entry for userId=$userId and origin=${abookRawInfo.origin} already exists: $oldVal")
          e
        }
        case None => {
          val saved = abookRepo.save(abook)
          log.info(s"[upload] created new abook entry for $userId and ${abookRawInfo.origin} saved entry=$saved")
          saved
        }
      }
      entry
    }

    // TODO: delay-batch-insert to contactRepo

    Ok(Json.toJson(abookRepoEntry))
  }

  // for testing only
  def getContactsRawInfo(userId:Id[User],origin:ABookOriginType) = Action { request =>
    val stored = s3.get(s"${userId}_$origin")
    log.info(s"userId=$userId origin=$origin stored=$stored")
    Ok(Json.toJson(stored))
  }

}