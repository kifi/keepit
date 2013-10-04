package com.keepit.abook

import com.google.inject.Inject
import com.keepit.common.db.slick.Database
import com.keepit.common.controller.{ABookServiceController, ActionAuthenticator}
import com.keepit.model._
import com.keepit.common.db.Id
import play.api.mvc.Action
import com.keepit.abook.store.{ABookRawInfoStore}
import play.api.libs.json.{JsValue, Json}
import scala.Some

class ABookController @Inject() (
  actionAuthenticator:ActionAuthenticator,
  db:Database,
  s3:ABookRawInfoStore,
  abookRepo:ABookRepo,
  contactInfoRepo:ContactInfoRepo
) extends ABookServiceController {


  private def toS3Key(userId:Id[User], origin:ABookOriginType) = s"${userId.id}_${origin.name}"

  // for testing only
  def upload(userId:Id[User], origin:ABookOriginType) = Action(parse.json(maxLength = 1024 * 5000)) { request =>
    val abookRawInfoRes = Json.fromJson[ABookRawInfo](request.body)
    val abookRawInfo = abookRawInfoRes.getOrElse(throw new Exception(s"Cannot parse ${request.body}"))

    val s3Key = toS3Key(userId, origin)
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
    val stored = s3.get(toS3Key(userId, origin))
    log.info(s"userId=$userId origin=$origin stored=$stored")
    Ok(Json.toJson(stored))
  }


  def getAllContactsRawInfo(userId:Id[User]) = Action { request =>
    val abooks = db.readOnly { implicit session =>
      abookRepo.findByUserId(userId)
    }
    val abookRawInfos = abooks.foldLeft(Seq.empty[ABookRawInfo]) { (a, c) =>
      a ++ {
        c.rawInfoLoc match {
          case Some(key) => {
            val stored = s3.get(key)
            log.info(s"[getContactsRawInfo(${userId}) key=$key stored=$stored")
            stored match {
              case Some(abookRawInfo) => {
//                val res = Json.fromJson[Seq[ContactInfo]](abookRawInfo.contacts).get
//                log.info(s"[getContactsRawInfo(${userId}) s3.get($key)=$res")
//                res
                Seq(abookRawInfo)
              }
              case None => Seq.empty[ABookRawInfo]
            }
          }
          case None => Seq.empty[ABookRawInfo]
        }
      }
    }
    log.info(s"[getContactsRawInfo(${userId})=$abookRawInfos")
    val json = Json.toJson(abookRawInfos)
    Ok(json)
  }

  def getABooksInfo(userId:Id[User]) = Action { request =>
    val abooks = db.readOnly { implicit session =>
      abookRepo.findByUserId(userId)
    }
    Ok(Json.toJson(abooks))
  }

}