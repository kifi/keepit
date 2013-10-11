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
  def upload(userId:Id[User], origin:ABookOriginType) = Action(parse.json(maxLength = 1024 * 50000)) { request =>
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
    origin match {
      case ABookOrigins.IOS => {
        val contactInfoBuilder = mutable.ArrayBuilder.make[ContactInfo]
        abookRawInfo.contacts.value.foreach { contact =>
          val firstName = (contact \ "firstName").as[String]
          val lastName = (contact \ "lastName").as[String]
          val emails = (contact \ "emails").as[Seq[String]]
          emails.foreach { email =>
            val cInfo = ContactInfo(
              userId = userId,
              origin = ABookOrigins.IOS,
              abookId = abookRepoEntry.id.get,
              name = Some(s"${firstName} ${lastName}".trim),
              firstName = Some(firstName),
              lastName = Some(lastName),
              email = email)
            log.info(s"[upload($userId,$origin)] contact=$cInfo")
            contactInfoBuilder += cInfo
          }
        }
        val contactInfos = contactInfoBuilder.result
        log.info(s"[upload($userId,$origin) #contacts=${contactInfos.length} contacts=${contactInfos.mkString(File.separator)}")
        if (!contactInfos.isEmpty) {
          // TODO: optimize
          db.readWrite { implicit session =>
            contactInfos.foreach { contactInfoRepo.save(_) }
          }
        }
      }
      case _ => // not (yet) handled
    }
    Ok(Json.toJson(abookRepoEntry))
  }

  object GMailCSVFields { // tied to Gmail CSV format
    val FIRST_NAME = "First Name"
    val LAST_NAME  = "Last Name"
    val EMAIL1     = "E-mail Address"
    val EMAIL2     = "E-mail 2 Address"
    val EMAIL3     = "E-mail 3 Address"
    // TODO: add other fields of interests
    val ALL = Seq(FIRST_NAME, LAST_NAME, EMAIL1, EMAIL2, EMAIL3)
    val MIN = Seq(FIRST_NAME, LAST_NAME, EMAIL1)
  }

  def withFields(fields:Array[String], locations:Map[String, Int])(key:String):String = {
    val loc = locations.get(key).getOrElse(-1)
    if (loc < 0 || loc >= fields.length) "" else fields(loc)
  }

  // for testing only
  def uploadGMailCSV(userId:Id[User]) = Action(parse.anyContent) { request =>
    val csvFilePart = request.body.asMultipartFormData.get.file("gmail_csv") // TODO: revisit
    val csvFile = File.createTempFile("abook", "csv")
    csvFilePart.getOrElse(throw new IllegalArgumentException("form field gmail_csv not found")).ref.moveTo(csvFile, true)

    log.info(s"[uploadGMailCSV] filePart=$csvFilePart file=${csvFile.getCanonicalFile} len=${csvFile.length}")

    var headers:Seq[String] = Seq.empty[String]
    var fieldLocations:Map[String, Int] = Map.empty[String, Int]
    val contactInfoBuilder = mutable.ArrayBuilder.make[ContactInfo]

    val savedABookInfo = db.readWrite { implicit session =>
      val abookInfo = ABookInfo(userId = userId, origin = ABookOrigins.GMAIL)
      val savedABookInfo = abookInfoRepo.save(abookInfo)
      savedABookInfo
    }

    for (line <- Source.fromFile(csvFile)(io.Codec("iso-8859-1")).getLines) { // easier for testing; needs UTF-8 for real usage
      if (headers.isEmpty) {
        headers = line.split(',') // TODO: REMOVEME
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
        val fields = line.split(',') // TODO: REMOVEME
        val f = withFields(fields, fieldLocations) _
        val firstName = f(FIRST_NAME)
        val lastName = f(LAST_NAME)
        val email = f(EMAIL1)

        if (!email.isEmpty) {
          val cInfo = ContactInfo(
            userId = userId,
            origin = ABookOrigins.GMAIL,
            abookId = savedABookInfo.id.get,
            name = Some(s"${firstName} ${lastName}".trim),
            firstName = Some(firstName),
            lastName = Some(lastName),
            email = email)
          log.info(s"[uploadGmailCSV] cInfo=${cInfo}")
          contactInfoBuilder += cInfo

          val optFields = Seq(EMAIL2, EMAIL3)
          for (opt <- optFields) {
            val e = f(opt)
            if (!e.isEmpty) {
              val optInfo = cInfo.withEmail(e)
              log.info(s"[uploadGmailCSV] optInfo=${optInfo}")
              contactInfoBuilder += optInfo // TODO: parentId
            }
          }
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