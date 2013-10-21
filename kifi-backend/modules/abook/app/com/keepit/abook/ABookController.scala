package com.keepit.abook

import com.google.inject.Inject
import com.keepit.common.db.slick.Database
import com.keepit.common.controller.{WebsiteController, ABookServiceController, ActionAuthenticator}
import com.keepit.model._
import com.keepit.common.db.Id
import play.api.mvc.{AsyncResult, Action}
import com.keepit.abook.store.{ABookRawInfoStore}
import play.api.libs.json.{JsObject, JsArray, JsValue, Json}
import scala.Some
import java.io.File
import scala.collection.{immutable, mutable}
import scala.io.Source
import scala.ref.WeakReference
import scala.concurrent.{Await, Future}
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.ws.{WS, Response}
import scala.xml.PrettyPrinter
import scala.concurrent.duration._

class ABookController @Inject() (
  actionAuthenticator:ActionAuthenticator,
  db:Database,
  s3:ABookRawInfoStore,
  abookInfoRepo:ABookInfoRepo,
  contactInfoRepo:ContactInfoRepo,
  contactsUpdater:ContactsUpdaterPlugin
) extends WebsiteController(actionAuthenticator) with ABookServiceController {

  def importContacts(userId:Id[User], provider:String, accessToken:String) = Action { request =>
    provider match {
      case "google" => {
        val userInfoUrl = s"https://www.googleapis.com/oauth2/v2/userinfo?access_token=$accessToken"
        val userInfoResp = Await.result(WS.url(userInfoUrl).get, 5 seconds).body
        log.info(s"[g-contacts] userInfoResp=${userInfoResp}")

        val contactsUrl = s"https://www.google.com/m8/feeds/contacts/default/full?access_token=${accessToken}&max-results=${Int.MaxValue}" // TODO: paging (alt=json doesn't work)
        val contactsResp: Response = Await.result(WS.url(contactsUrl).get, 10 seconds)
        if (contactsResp.status == OK) {
          val contacts = contactsResp.xml // TODO: optimize; hand-off
          log.info(s"[g-contacts] $contacts")
          val prettyPrint = new PrettyPrinter(300, 2)
          val sb = new StringBuilder
          prettyPrint.format(contacts, sb) // TODO: REMOVEME
          log.info(s"[g-contacts] ${sb.toString}")
          val jsArrays: immutable.Seq[JsArray] = (contacts \\ "feed").map { feed =>
            val gId = (feed \ "id").text
            log.info(s"[g-contacts] id=$gId")
            val entries: Seq[JsObject] = (feed \ "entry").map { entry =>
              val title = (entry \ "title").text
              val emails = (entry \ "email").map(_ \ "@address")
              log.info(s"[g-contacts] title=$title email=$emails")
              Json.obj("name" -> title, "emails" -> Json.toJson(emails.seq.map(_.toString)))
            }
            JsArray(entries)
          }

          val abookUpload = Json.obj("origin" -> "gmail", "contacts" -> jsArrays(0))
          log.info(Json.prettyPrint(abookUpload))
          val abookInfo = processUpload(userId, ABookOrigins.GMAIL, abookUpload)
          Ok(Json.toJson(abookInfo))
        } else {
          BadRequest(s"Failed to retrieve gmail contacts. Contacts API response: ${contactsResp}")
        }
      }
      case "facebook" => { // testing only
      val friendsUrl = s"https://graph.facebook.com/me/friends?access_token=${accessToken}&fields=id,name,first_name,last_name,username,picture,email"
        val friends = Await.result(WS.url(friendsUrl).get, 5 seconds).json
        log.info(s"[facebook] friends:\n${Json.prettyPrint(friends)}")
        Ok(friends)
      }
    }
  }

  private def toS3Key(userId:Id[User], origin:ABookOriginType) = s"${userId.id}_${origin.name}"

  // authenticated upload (used by ios/mobile)
  def upload(origin:ABookOriginType) = AuthenticatedJsonAction(false, parse.json(maxLength = 1024 * 50000)) { request =>
    val userId = request.userId
    val json = request.body
    val abookRepoEntryF: Future[ABookInfo] = Future {
      processUpload(userId, origin, json)
    }
    val async: AsyncResult = Async {
      abookRepoEntryF.map(e => Ok(Json.toJson(e)))
    }
    async
  }

  // upload JSON file via form (for testing only)
  def uploadJson(userId:Id[User], origin:ABookOriginType) = Action(parse.multipartFormData) { request =>
    val jsonFilePart = request.body.file("abook_json")
    val jsonFile = File.createTempFile("abook_json", "json")
    jsonFilePart.getOrElse(throw new IllegalArgumentException("form field ios_json not found")).ref.moveTo(jsonFile, true)
    val jsonSrc = Source.fromFile(jsonFile)(io.Codec("UTF-8")).getLines.foldLeft("") { (a,c) => a + c }
    log.info(s"[upload($userId, $origin)] jsonFile=$jsonFile jsonSrc=$jsonSrc")
    val json = Json.parse(jsonSrc) // for testing
    log.info(s"[uploadJson] json=${Json.prettyPrint(json)}")
    val abookInfoRepoEntry = processUpload(userId, origin, json)
    Ok(Json.toJson(abookInfoRepoEntry))
  }

  // direct JSON-upload (used by gmail import)
  def uploadJsonDirect(userId:Id[User], origin:ABookOriginType) = Action(parse.json(maxLength = 1024 * 50000)) { request =>
    val json = request.body
    log.info(s"[uploadJsonDirect] json=${Json.prettyPrint(json)}")
    val abookInfoRepoEntry = processUpload(userId, origin, json)
    Ok(Json.toJson(abookInfoRepoEntry))
  }

  private def processUpload(userId: Id[User], origin: ABookOriginType, json: JsValue): ABookInfo = {
    val abookRawInfoRes = Json.fromJson[ABookRawInfo](json)
    val abookRawInfo = abookRawInfoRes.getOrElse(throw new Exception(s"Cannot parse ${json}"))

    val s3Key = toS3Key(userId, origin)
    s3 += (s3Key -> abookRawInfo) // TODO: put on queue
    log.info(s"[upload] s3Key=$s3Key rawInfo=$abookRawInfo}")

    // TODO: cache (if needed)

    val abookRepoEntry = db.readWrite {
      implicit session =>
        val abook = ABookInfo(userId = userId, origin = abookRawInfo.origin, rawInfoLoc = Some(s3Key))
        val oldVal = abookInfoRepo.findByUserIdAndOriginOpt(userId, abookRawInfo.origin)
        val entry = oldVal match {
          case Some(abookInfoEntry) => {
            log.info(s"[upload] old entry for userId=$userId and origin=${abookRawInfo.origin} already exists: $oldVal")
            val deletedRows = contactInfoRepo.deleteByUserIdAndABookInfo(userId, abookInfoEntry.id.get) // TODO:REVISIT
            log.info(s"[upload] # of rows deleted=$deletedRows")
            abookInfoEntry
          }
          case None => {
            val saved = abookInfoRepo.save(abook)
            log.info(s"[upload] created new abook entry for $userId and ${abookRawInfo.origin} saved entry=$saved")
            saved
          }
        }
        entry
    }

    contactsUpdater.asyncProcessContacts(userId, origin, abookRepoEntry, s3Key, WeakReference(json))
    log.info(s"[upload] created abookEntry: $abookRepoEntry")
    abookRepoEntry
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

  // for testing only -- to be removed
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

  // for testing only -- to be removed
  def getContactsRawInfo(userId:Id[User],origin:ABookOriginType) = Action { request =>
    Async {
      Future {
        val stored = s3.get(toS3Key(userId, origin))
        log.info(s"userId=$userId origin=$origin stored=$stored")
        Json.toJson(stored)
      }.map(js => Ok(js))
    }
  }

  def getContactInfos(userId:Id[User], maxRows:Int) = Action { request =>
    val resF:Future[JsValue] = Future {
      val ts = System.currentTimeMillis
      val jsonBuilder = mutable.ArrayBuilder.make[JsValue]
      db.readOnly { implicit session =>
        contactInfoRepo.getByUserIdIter(userId, maxRows).foreach { jsonBuilder += Json.toJson(_) } // TODO: paging & caching
      }
      val contacts = jsonBuilder.result
      log.info(s"[getContactInfos($userId, $maxRows)] # of contacts returned: ${contacts.length} time-lapsed: ${System.currentTimeMillis - ts}")
      JsArray(contacts)
    }
    val async: AsyncResult = Async {
      resF.map { js => Ok(js) }
    }
    async
  }

  def getABookRawInfos(userId:Id[User]) = Action { request =>
    val resF:Future[JsValue] = Future {
      val abookInfos = db.readOnly { implicit session =>
        abookInfoRepo.findByUserId(userId)
      }
      val abookRawInfos = abookInfos.foldLeft(Seq.empty[ABookRawInfo]) { (a, c) =>
        a ++ {
          for { k <- c.rawInfoLoc
                v <- s3.get(k) } yield v
        }
      }
      val json = Json.toJson(abookRawInfos)
      log.info(s"[getContactsRawInfo(${userId})=$abookRawInfos json=$json")
      json
    }
    Async {
      resF.map(js => Ok(js))
    }
  }

  def getABookInfos(userId:Id[User]) = Action { request =>
    val resF:Future[JsValue] = Future {
      val abookInfos = db.readOnly { implicit session =>
        abookInfoRepo.findByUserId(userId)
      }
      Json.toJson(abookInfos)
    }
    Async {
      resF.map(js => Ok(js))
    }
  }

}