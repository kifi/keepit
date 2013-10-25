package com.keepit.abook

import com.google.inject.Inject
import com.keepit.common.db.slick.Database
import com.keepit.common.controller.{WebsiteController, ABookServiceController, ActionAuthenticator}
import com.keepit.model._
import com.keepit.common.db.Id
import play.api.mvc.{SimpleResult, AsyncResult, Action}
import com.keepit.abook.store.{ABookRawInfoStore}
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
import play.api.libs.functional.syntax._
import play.api.libs.json._

// provider-specific
class ABookOwnerInfo(val id:Option[String], val email:Option[String] = None)

object ABookOwnerInfo {
  def apply(id:Option[String], email:Option[String]) = new ABookOwnerInfo(id, email)
  def unapply(ownerInfo:ABookOwnerInfo):Option[(Option[String],Option[String])] = Some(ownerInfo.id, ownerInfo.email)
  val EMPTY = ABookOwnerInfo(None, None)
}

class GmailABookOwnerInfo(id:Option[String], email:Option[String], val verified:Option[Boolean] = None, val hd:Option[String] = None) extends ABookOwnerInfo(id, email)

object GmailABookOwnerInfo {
  def apply(id:Option[String], email:Option[String], verified:Option[Boolean], hd:Option[String]) = new GmailABookOwnerInfo(id, email, verified, hd)
  def unapply(userInfo:GmailABookOwnerInfo):Option[(Option[String], Option[String], Option[Boolean], Option[String])] = Some(userInfo.id, userInfo.email, userInfo.verified, userInfo.hd)

  implicit val format = (
    (__ \ 'id).formatNullable[String] and
    (__ \ 'email).formatNullable[String] and
    (__ \ 'verified_email).formatNullable[Boolean] and
    (__ \ 'hd).formatNullable[String]
  )(GmailABookOwnerInfo.apply, unlift(GmailABookOwnerInfo.unapply))

  val EMPTY = GmailABookOwnerInfo(None, None, None, None)
}

class ABookController @Inject() (
  actionAuthenticator:ActionAuthenticator,
  db:Database,
  s3:ABookRawInfoStore,
  abookInfoRepo:ABookInfoRepo,
  contactRepo:ContactRepo,
  contactInfoRepo:ContactInfoRepo,
  contactsUpdater:ContactsUpdaterPlugin
) extends WebsiteController(actionAuthenticator) with ABookServiceController {

  def importContacts(userId:Id[User], provider:String, accessToken:String) = Action { request =>
    provider match {
      case "google" => {
        val userInfoUrl = s"https://www.googleapis.com/oauth2/v2/userinfo?access_token=$accessToken"
        val userInfoJson = Await.result(WS.url(userInfoUrl).get, 5 seconds).json
        val gUserInfo = userInfoJson.as[GmailABookOwnerInfo]
        log.info(s"[g-contacts] userInfoResp=${userInfoJson} googleUserInfo=${gUserInfo}")

        val contactsUrl = s"https://www.google.com/m8/feeds/contacts/default/full?access_token=${accessToken}&max-results=${Int.MaxValue}" // TODO: paging (alt=json ignored)
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

          val abookUpload = Json.obj("origin" -> "gmail", "ownerId" -> gUserInfo.id, "contacts" -> jsArrays(0))
          log.info(Json.prettyPrint(abookUpload))
          val abookInfo = processUpload(userId, ABookOrigins.GMAIL, Some(gUserInfo), abookUpload)
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

  private def toS3Key(userId:Id[User], origin:ABookOriginType, abookOwnerInfo:Option[ABookOwnerInfo]):String = {
    val k = s"${userId.id}_${origin.name}"
    val ownerId = for (abookOwner <- abookOwnerInfo; ownerId <- abookOwner.id) yield ownerId
    ownerId match {
      case Some(id) => s"${k}_${id}"
      case None => k
    }
  }

  // authenticated upload (used by ios/mobile)
  def upload(origin:ABookOriginType) = AuthenticatedJsonAction(false, parse.json(maxLength = 1024 * 50000)) { request =>
    val userId = request.userId
    val json = request.body
    val abookRepoEntryF: Future[ABookInfo] = Future {
      processUpload(userId, origin, None, json)
    }
    Async {
      abookRepoEntryF.map(e => Ok(Json.toJson(e)))
    }
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
    val abookInfoRepoEntry = processUpload(userId, origin, None, json)
    Ok(Json.toJson(abookInfoRepoEntry))
  }

  // direct JSON-upload (for testing only)
  def uploadJsonDirect(userId:Id[User], origin:ABookOriginType) = Action(parse.json(maxLength = 1024 * 50000)) { request =>
    val json = request.body
    log.info(s"[uploadJsonDirect($userId,$origin)] json=${Json.prettyPrint(json)}")
    val abookInfoRepoEntry = processUpload(userId, origin, None, json)
    Ok(Json.toJson(abookInfoRepoEntry))
  }

  // shared
  private[abook] def processUpload(userId: Id[User], origin: ABookOriginType, ownerInfoOpt:Option[ABookOwnerInfo], json: JsValue): ABookInfo = {
    val abookRawInfoRes = Json.fromJson[ABookRawInfo](json)
    val abookRawInfo = abookRawInfoRes.getOrElse(throw new Exception(s"Cannot parse ${json}"))

    val s3Key = toS3Key(userId, origin, ownerInfoOpt)
    s3 += (s3Key -> abookRawInfo)
    log.info(s"[upload($userId,$origin)] s3Key=$s3Key rawInfo=$abookRawInfo}")

    val abookInfoEntry = db.readWrite { implicit session =>
      val (abookInfo, entryOpt) = origin match {
        case ABookOrigins.IOS => { // no ownerInfo -- revisit later
          val abookInfo = ABookInfo(userId = userId, origin = abookRawInfo.origin, rawInfoLoc = Some(s3Key))
          val entryOpt = {
            val s = abookInfoRepo.findByUserIdAndOrigin(userId, origin)
            if (s.isEmpty) None else Some(s(0))
          }
          (abookInfo, entryOpt)
        }
        case ABookOrigins.GMAIL => {
          val ownerInfo = ownerInfoOpt.getOrElse(throw new IllegalArgumentException("Owner info not set for $userId and $origin"))
          val abookInfo = ABookInfo(userId = userId, origin = abookRawInfo.origin, ownerId = ownerInfo.id, ownerEmail = ownerInfo.email, rawInfoLoc = Some(s3Key))
          val entryOpt = abookInfoRepo.findByUserIdOriginAndOwnerId(userId, origin, abookInfo.ownerId)
          (abookInfo, entryOpt)
        }
      }
      val entry = entryOpt match {
        case Some(oldVal) => {
          log.info(s"[upload($userId,$origin)] current entry: $oldVal")
          oldVal
        }
        case None => abookInfoRepo.save(abookInfo)
      }
      entry
    }
    contactsUpdater.asyncProcessContacts(userId, origin, abookInfoEntry, s3Key, WeakReference(json))
    log.info(s"[upload($userId,$origin)] created abookEntry: $abookInfoEntry")
    abookInfoEntry
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

  def getContacts(userId:Id[User], maxRows:Int) = Action { request =>
    val resF:Future[JsValue] = Future {
      getContactsDirect(userId, maxRows)
    }
    val async: AsyncResult = Async {
      resF.map { js => Ok(js) }
    }
    async
  }

  def getContactsDirect(userId: Id[User], maxRows: Int): JsArray = {
    val ts = System.currentTimeMillis
    val jsonBuilder = mutable.ArrayBuilder.make[JsValue]
    db.readOnly {
      implicit session =>
        contactRepo.getByUserIdIter(userId, maxRows).foreach {
          jsonBuilder += Json.toJson(_)
        } // TODO: paging & caching
    }
    val contacts = jsonBuilder.result
    log.info(s"[getContacts($userId, $maxRows)] # of contacts returned: ${contacts.length} time-lapsed: ${System.currentTimeMillis - ts}")
    JsArray(contacts)
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

  def getSimpleContactInfos(userId:Id[User], maxRows:Int) = Action { request =>
    val resF:Future[JsValue] = Future {
      val jsonBuilder = mutable.ArrayBuilder.make[JsValue]
      db.readOnly { implicit session =>
        contactRepo.getByUserIdIter(userId, maxRows).foreach { c =>
          jsonBuilder += {
            Json.obj("name" -> c.name, "emails" -> {
              Seq(c.email) ++ {
                c.altEmails.map { s =>
                  val js = Json.parse(s)
                  js.validate[Seq[String]].fold(
                    valid = ( res => res.seq ),
                    invalid = ( e => {
                      log.error(s"[getSimpleContactInfos] cannot parse $s")
                      Seq.empty[String]
                    } )
                  )
                }.getOrElse(Seq.empty[String])
              }
            })
          }
        }
      }
      val contacts = jsonBuilder.result
      JsArray(contacts)
    }
    val res = Async {
      resF.map { js => Ok(js) }
    }
    res
  }

  // cache
  def getMergedContactInfos(userId:Id[User], maxRows:Int) = Action { request =>
    val resF:Future[JsValue] = Future {
      val m = new mutable.HashMap[String, Set[String]]()
      db.readOnly { implicit session =>
        contactRepo.getByUserIdIter(userId, maxRows).foreach { c => // assume c.name exists (fix import)
          val emails = Set(c.email) ++ {
            c.altEmails.map { s =>
              val js = Json.parse(s)
              js.validate[Seq[String]].fold(
                valid = (res => res.seq.toSet),
                invalid = ( e => {
                  log.error(s"[getMergedContactInfos] cannot parse $s error: $e")
                  Set.empty[String]
                })
              )
            }.getOrElse(Set.empty[String])
          }
          m.put(c.name.get, m.get(c.name.get).getOrElse(Set.empty[String]) ++ emails)
        }
      }
      val jsonBuilder = mutable.ArrayBuilder.make[JsValue]
      m.keysIterator.foreach { k =>
        jsonBuilder += Json.obj("name" -> k, "emails" -> Json.toJson(m.get(k).get))
      }
      val contacts = jsonBuilder.result
      JsArray(contacts)
    }
    val res = Async {
      resF.map { js => Ok(js) }
    }
    res
  }

  def getABookRawInfos(userId:Id[User]) = Action { request =>
    val resF:Future[JsValue] = Future {
      getABookRawInfosDirect(userId)
    }
    Async {
      resF.map(js => Ok(js))
    }
  }

  private [abook] def getABookRawInfosDirect(userId: Id[User]): JsValue = {
    val abookInfos = db.readOnly {
      implicit session =>
        abookInfoRepo.findByUserId(userId)
    }
    val abookRawInfos = abookInfos.foldLeft(Seq.empty[ABookRawInfo]) {
      (a, c) =>
        a ++ {
          for {k <- c.rawInfoLoc
               v <- s3.get(k)} yield v
        }
    }
    val json = Json.toJson(abookRawInfos)
    log.info(s"[getContactsRawInfo(${userId})=$abookRawInfos json=$json")
    json
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

  // retrieve from S3
  def getContactsRawInfo(userId:Id[User],origin:ABookOriginType) = Action { request =>
    Async {
      Future {
        val abooks = db.readOnly { implicit session =>
          abookInfoRepo.findByUserIdAndOrigin(userId, origin)
        }
        abooks.map{ abookInfo =>
          val key = abookInfo.rawInfoLoc.getOrElse(
            origin match {
              case ABookOrigins.IOS => toS3Key(userId, origin, None) // only ok for IOS
              case _ => throw new IllegalStateException(s"[getContactsRawInfo($userId, $origin)] rawInfoLoc not set for $abookInfo")
            }
          )
          val stored = s3.get(key)
          log.info(s"userId=$userId origin=$origin stored=$stored")
          Json.toJson[ABookRawInfo](stored.getOrElse(ABookRawInfo.EMPTY))
        }
      }.map(js => Ok(JsArray(js)))
    }
  }

  // for testing only -- will be removed
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

}