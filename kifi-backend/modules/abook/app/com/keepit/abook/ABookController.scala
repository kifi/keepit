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
import java.io.File
import scala.collection.mutable
import scala.io.Source

class ABookController @Inject() (
  actionAuthenticator:ActionAuthenticator,
  db:Database,
  s3:ABookRawInfoStore,
  abookInfoRepo:ABookInfoRepo,
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
      val abook = ABookInfo(userId = userId, origin = abookRawInfo.origin, rawInfoLoc = Some(s3Key))
      val oldVal = abookInfoRepo.findByUserIdAndOriginOpt(userId, abookRawInfo.origin)
      val entry = oldVal match {
        case Some(e) => {
          log.info(s"[upload] old entry for userId=$userId and origin=${abookRawInfo.origin} already exists: $oldVal")
          e
        }
        case None => {
          val saved = abookInfoRepo.save(abook)
          log.info(s"[upload] created new abook entry for $userId and ${abookRawInfo.origin} saved entry=$saved")
          saved
        }
      }
      entry
    }

    // TODO: delay-batch-insert to contactRepo

    Ok(Json.toJson(abookRepoEntry))
  }

  object GMailCSVFields { // tied to Gmail CSV format
    val FIRST_NAME = "First Name"
    val LAST_NAME  = "Last Name"
    val EMAIL1     = "E-mail Address"
    val EMAIL2     = "E-mail 2 Address"
    val EMAIL3     = "E-mail 3 Address"
    val ALL = Seq(FIRST_NAME, LAST_NAME, EMAIL1, EMAIL2, EMAIL3)
    val MIN = Seq(FIRST_NAME, LAST_NAME, EMAIL1)
  }

  // for testing only
  def uploadGMailCSV(userId:Id[User]) = Action(parse.anyContent) { request =>
    val csvFilePart = request.body.asMultipartFormData.get.file("gmail_csv") // TODO: revisit
    val csvFile = csvFilePart.get.ref.file
    log.info(s"[uploadGMailCSV] filePart=$csvFilePart file=$csvFile")

    var headers:Seq[String] = Seq.empty[String]
    var fieldLocations:Map[String, Int] = Map.empty[String, Int]
    val contactInfoBuilder = mutable.ArrayBuilder.make[ContactInfo]

    val savedABookInfo = db.readWrite { implicit session =>
      val abookInfo = ABookInfo(userId = userId, origin = ABookOrigins.GMAIL)
      val savedABookInfo = abookInfoRepo.save(abookInfo)
      savedABookInfo
    }

    for (line <- Source.fromFile(csvFile).getLines) {
      if (headers.isEmpty) {
        headers = line.split(',').toSeq
        fieldLocations = GMailCSVFields.ALL.foldLeft(Map.empty[String, Int]) { (a, c) =>
          val idx = headers.indexOf(c)
          if (idx != -1) {
            a + (c -> idx)
          } else a
        }
        if (fieldLocations.isEmpty || GMailCSVFields.MIN.forall(fieldLocations.get(_).isEmpty)) throw new IllegalArgumentException("invalid csv format")
        log.info(s"[uploadGmailCSV] #headers=${headers.size} fieldLocations=${fieldLocations}")
      } else {
        import GMailCSVFields._
        val fields = line.split(',')
        val firstName = fields(fieldLocations.get(FIRST_NAME).get)
        val lastName  = fields(fieldLocations.get(LAST_NAME).get)
        val email = fields(fieldLocations.get(EMAIL1).get)
        val contactInfo = ContactInfo(userId = userId, origin = ABookOrigins.GMAIL, abookId = savedABookInfo.id.get, name = Some(s"$firstName $lastName"), firstName = Some(firstName), lastName = Some(lastName), email = email)
        contactInfoBuilder += contactInfo
        fieldLocations.get(EMAIL2).map { idx =>
          val e2 = fields(idx)
          if (!e2.isEmpty) contactInfoBuilder += contactInfo.withEmail(e2) // TODO: parentId
        }
        fieldLocations.get(EMAIL3).map { idx =>
          val e3 = fields(idx)
          if (!e3.isEmpty) contactInfoBuilder += contactInfo.withEmail(e3)
        }
      }
    }
    val contactInfos = contactInfoBuilder.result
    if (!contactInfos.isEmpty) {
      // TODO: optimize
      db.readWrite { implicit session =>
        contactInfos.foreach { contactInfoRepo.save(_) }
      }
    }
    log.info(s"[uploadGmailCSV] # contacts saved = ${contactInfos.length}")

    Ok("Contacts uploaded")
  }

  // for testing only
  def getContactsRawInfo(userId:Id[User],origin:ABookOriginType) = Action { request =>
    val stored = s3.get(toS3Key(userId, origin))
    log.info(s"userId=$userId origin=$origin stored=$stored")
    Ok(Json.toJson(stored))
  }

  def getContactInfos(userId:Id[User]) = Action { request =>
    val contactInfos = db.readOnly { implicit session =>
      contactInfoRepo.getByUserIdIter(userId, 5000) // TODO: handle large sizes
    }.toSeq // TODO: optimize
    Ok(Json.toJson(contactInfos))
  }

  def getABookRawInfos(userId:Id[User]) = Action { request =>
    val abookInfos = db.readOnly { implicit session =>
      abookInfoRepo.findByUserId(userId)
    }
    val abookRawInfos = abookInfos.foldLeft(Seq.empty[ABookRawInfo]) { (a, c) =>
      a ++ {
        c.rawInfoLoc match {
          case Some(key) => {
            val stored = s3.get(key)
            log.info(s"[getContactsRawInfo(${userId}) key=$key stored=$stored")
            stored match {
              case Some(abookRawInfo) => {
                Seq(abookRawInfo)
              }
              case None => Seq.empty[ABookRawInfo]
            }
          }
          case None => Seq.empty[ABookRawInfo]
        }
      }
    }
    val json = Json.toJson(abookRawInfos)
    log.info(s"[getContactsRawInfo(${userId})=$abookRawInfos json=$json")
    Ok(json)
  }

  def getABookInfos(userId:Id[User]) = Action { request =>
    val abookInfos = db.readOnly { implicit session =>
      abookInfoRepo.findByUserId(userId)
    }
    Ok(Json.toJson(abookInfos))
  }

}