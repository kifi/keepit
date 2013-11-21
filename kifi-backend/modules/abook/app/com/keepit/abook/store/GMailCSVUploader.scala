package com.keepit.abook.store

import com.keepit.common.db.Id
import com.keepit.model.{ABookOrigins, ABookInfo, Contact, User}
import java.io.File
import scala.collection.mutable
import scala.io.Source
import com.keepit.common.controller.{ActionAuthenticator, WebsiteController}
import com.keepit.common.db.slick.Database
import com.keepit.abook._
import scala.Some
import com.google.inject.Inject
import com.keepit.common.logging.Logging
import play.api.mvc.Action

// for testing only -- can be removed
class GMailCSVUploader @Inject() (actionAuthenticator:ActionAuthenticator,
  db:Database,
  s3: ABookRawInfoStore,
  abookInfoRepo: ABookInfoRepo,
  contactRepo: ContactRepo,
  econtactRepo: EContactRepo,
  contactsUpdater: ContactsUpdaterPlugin
) extends WebsiteController(actionAuthenticator) with Logging {

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

  // for testing only -- will be removed
  def upload(userId:Id[User]) = Action(parse.anyContent) { request =>
    val csvFilePart = request.body.asMultipartFormData.get.file("gmail_csv") // TODO: revisit
  val csvFile = File.createTempFile("abook", "csv")
    csvFilePart.getOrElse(throw new IllegalArgumentException("form field gmail_csv not found")).ref.moveTo(csvFile, true)

    log.info(s"[uploadGMailCSV] filePart=$csvFilePart file=${csvFile.getCanonicalFile} len=${csvFile.length}")

    var headers:Seq[String] = Seq.empty[String]
    var fieldLocations:Map[String, Int] = Map.empty[String, Int]
    val builder = mutable.ArrayBuilder.make[Contact]

    val savedABookInfo = db.readWrite(attempts = 2) { implicit session =>
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
          val cInfo = Contact(
            userId = userId,
            origin = ABookOrigins.GMAIL,
            abookId = savedABookInfo.id.get,
            name = Some(s"${firstName} ${lastName}".trim),
            firstName = Some(firstName),
            lastName = Some(lastName),
            email = email)
          log.info(s"[uploadGmailCSV] cInfo=${cInfo}")
          builder += cInfo

          // broken but will likely remove this class anyway
//          val optFields = Seq(EMAIL2, EMAIL3)
//          for (opt <- optFields) {
//            val e = f(opt)
//            if (!e.isEmpty) {
//              val optInfo = cInfo.withEmail(e)
//              log.info(s"[uploadGmailCSV] optInfo=${optInfo}")
//              builder += optInfo // TODO: parentId
//            }
//          }
        }
      }
    }
    val contacts = builder.result
    if (!contacts.isEmpty) {
      // TODO: optimize
      db.readWrite(attempts = 2) { implicit session =>
        contacts.foreach { contactRepo.save(_) }
      }
    }
    log.info(s"[uploadGmailCSV] # contacts saved = ${contacts.length}")

    Ok("Contacts uploaded")
  }

}
