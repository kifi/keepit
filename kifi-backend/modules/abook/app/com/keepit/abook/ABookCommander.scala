package com.keepit.abook

import com.google.inject.Inject
import com.keepit.common.db.slick.Database
import com.keepit.abook.store.ABookRawInfoStore
import com.keepit.common.db.Id
import com.keepit.common.performance._
import com.keepit.model._
import play.api.libs.json.{JsObject, JsArray, Json, JsValue}
import scala.ref.WeakReference
import com.keepit.common.logging.{LogPrefix, Logging}
import scala.collection.mutable
import scala.concurrent._
import scala.concurrent.duration._
import com.keepit.common.time._
import com.keepit.common.db.slick._
import scala.util.{Try, Failure, Success}
import java.text.Normalizer
import play.api.libs.concurrent.Execution.Implicits._

import Logging._
import play.api.libs.ws.{Response, WS}
import play.api.http.Status
import com.keepit.common.healthcheck.AirbrakeNotifier
import scala.xml.Elem
import com.keepit.abook.typeahead.EContactTypeahead
import com.keepit.common.mail.{SystemEmailAddress, ElectronicMail, BasicContact, EmailAddress}
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.commanders.ContactInterner
import com.keepit.abook.model.{EContactRepo, EContactStates, EContact}

class ABookCommander @Inject() (
  db:Database,
  airbrake:AirbrakeNotifier,
  s3:ABookRawInfoStore,
  econtactTypeahead:EContactTypeahead,
  abookInfoRepo:ABookInfoRepo,
  econtactRepo:EContactRepo,
  abookImporter:ABookImporterPlugin,
  contactInterner: ContactInterner,
  shoebox: ShoeboxServiceClient
) extends Logging {

  def toS3Key(userId:Id[User], origin:ABookOriginType, abookOwnerInfo:Option[ABookOwnerInfo]):String = {
    val k = s"${userId.id}_${origin.name}"
    val ownerId = for (abookOwner <- abookOwnerInfo; ownerId <- abookOwner.id) yield ownerId
    ownerId match {
      case Some(id) => s"${k}_${id}"
      case None => k
    }
  }

  def getABookInfo(userId:Id[User], id:Id[ABookInfo]):Option[ABookInfo] = {
    db.readOnlyMaster(attempts = 2) { implicit s =>
      abookInfoRepo.getByUserIdAndABookId(userId, id)
    }
  }

  def failWithEx(s:String)(implicit prefix:LogPrefix) = {
    log.errorP(s)
    Failure(new IllegalStateException(s))
  }

  val USER_INFO_URL = "https://www.googleapis.com/oauth2/v2/userinfo"
  def getGmailOwnerInfo(userId: Id[User],accessToken: String):Future[GmailABookOwnerInfo] = {
    implicit val prefix = LogPrefix(s"getGmailOwnerInfo($userId)")

    for {
      resp <- WS.url(USER_INFO_URL).withQueryString(("access_token", accessToken)).get
    } yield {
      log.infoP(s"userinfo response=${resp.body}")
      resp.status match {
        case Status.OK => resp.json.asOpt[GmailABookOwnerInfo] match {
          case Some(info) => info
          case None => throw new IllegalStateException(s"$prefix cannot parse userinfo response: ${resp.body}")
        }
        case _ => throw new IllegalStateException(s"$prefix failed to obtain info about user/owner")
      }
    }
  }

  val CONTACTS_URL = "https://www.google.com/m8/feeds/contacts/default/full"
  def importGmailContacts(userId: Id[User],accessToken: String, tokenOpt:Option[OAuth2Token]):Future[ABookInfo] = {
    implicit val prefix = LogPrefix(s"importGmailContacts($userId)")

    @inline def xml2Js(contacts:Elem):Seq[JsObject] = {
      (contacts \ "entry") map { entry =>
        val title = (entry \ "title").text
        val emails = for {
          email <- (entry \ "email")
          addr  <- (email \ "@address")
        } yield {
          addr.text
        }
        log.infoP(s"title=$title email=$emails")
        Json.obj("name" -> title, "emails" -> Json.toJson(emails))
      }
    }

    val userInfoF:Future[GmailABookOwnerInfo] = getGmailOwnerInfo(userId, accessToken)
    val contactsRespF:Future[Response] = WS.url(CONTACTS_URL).withRequestTimeout(120000).withQueryString(("access_token", accessToken), ("max-results", Int.MaxValue.toString)).get
    for {
      userInfo <- userInfoF
      contactsResp <- contactsRespF
    } yield {
      contactsResp.status match {
        case Status.OK =>
          val contacts = timingWithResult[Elem](s"$prefix parse-XML") { contactsResp.xml } // todo(ray): paging

          val totalResults = (contacts \ "totalResults").text.toInt
          val startIndex   = (contacts \ "startIndex").text.toInt
          val itemsPerPage = (contacts \ "itemsPerPage").text.toInt
          log.infoP(s"total=$totalResults start=$startIndex itemsPerPage=$itemsPerPage")

          val jsSeq = timingWithResult[Seq[JsObject]](s"$prefix xml2Js") { xml2Js(contacts) }

          val abookUpload = Json.obj("origin" -> "gmail", "ownerId" -> userInfo.id, "numContacts" -> jsSeq.length, "contacts" -> jsSeq) // todo(ray): removeme
          processUpload(userId, ABookOrigins.GMAIL, Some(userInfo), tokenOpt, abookUpload) getOrElse (throw new IllegalStateException(s"$prefix failed to upload contacts ($abookUpload)"))
        case _ =>
          throw new IllegalStateException(s"$prefix failed to retrieve contacts; response: $contactsResp") // todo(ray): retry
      }
    }
  }

  def processUpload(userId: Id[User], origin: ABookOriginType, ownerInfoOpt:Option[ABookOwnerInfo], oauth2TokenOpt: Option[OAuth2Token], json: JsValue): Option[ABookInfo] = {
    implicit val prefix = LogPrefix(s"processUpload($userId,$origin)")
    log.infoP(s"ownerInfo=$ownerInfoOpt; oauth2Token=$oauth2TokenOpt; json=$json")
    val abookRawInfoRes = Json.fromJson[ABookRawInfo](json)
    val abookRawInfo = abookRawInfoRes.getOrElse(throw new Exception(s"Cannot parse ${json}"))
    log.infoP(s"rawInfo=$abookRawInfo")

    val numContacts = abookRawInfo.numContacts orElse { // todo(ray): remove when ios supplies numContacts
      (json \ "contacts").asOpt[JsArray] map { _.value.length }
    } getOrElse 0

    if (numContacts <= 0) None
    else {
      val s3Key = toS3Key(userId, origin, ownerInfoOpt)
      s3 += (s3Key -> abookRawInfo)
      log.infoP(s"s3Key=$s3Key rawInfo=$abookRawInfo}")

      val savedABookInfo = db.readWrite(attempts = 2) { implicit session =>
        val (abookInfo, dbEntryOpt) = origin match {
          case ABookOrigins.IOS => { // no ownerInfo or numContacts -- revisit later
          val abookInfo = ABookInfo(userId = userId, origin = abookRawInfo.origin, rawInfoLoc = Some(s3Key), oauth2TokenId = oauth2TokenOpt.flatMap(_.id), numContacts = Some(numContacts), state = ABookInfoStates.INACTIVE)
            val dbEntryOpt = {
              val s = abookInfoRepo.findByUserIdAndOrigin(userId, origin)
              if (s.isEmpty) None else Some(s(0))
            }
            (abookInfo, dbEntryOpt)
          }
          case ABookOrigins.GMAIL => {
            val ownerInfo = ownerInfoOpt.getOrElse(throw new IllegalArgumentException(s"Owner info not set for $userId and $origin"))
            val abookInfo = ABookInfo(userId = userId, origin = abookRawInfo.origin, ownerId = ownerInfo.id, ownerEmail = ownerInfo.email, rawInfoLoc = Some(s3Key), oauth2TokenId = oauth2TokenOpt.flatMap(_.id),  numContacts = Some(numContacts), state = ABookInfoStates.INACTIVE)
            val dbEntryOpt = abookInfoRepo.findByUserIdOriginAndOwnerId(userId, origin, abookInfo.ownerId)
            (abookInfo, dbEntryOpt)
          }

          case _ => throw new UnsupportedOperationException(s"Unsupported ABook origin $origin.")
        }
        val savedEntry = dbEntryOpt match {
          case Some(currEntry) => {
            log.infoP(s"current entry: $currEntry")
            abookInfoRepo.save(currEntry.withNumContacts(Some(numContacts)))
          }
          case None => abookInfoRepo.save(abookInfo)
        }
        savedEntry
      }
      val (proceed, updatedEntry) = if (savedABookInfo.state != ABookInfoStates.PENDING) {
        val updated = db.readWrite(attempts = 2) { implicit s =>
          abookInfoRepo.save(savedABookInfo.withState(ABookInfoStates.PENDING))
        }
        (true, updated)
      } else {
        val isOverdue = db.readOnlyMaster(attempts = 2) { implicit s =>
          abookInfoRepo.isOverdue(savedABookInfo.id.get, currentDateTime.minusMinutes(10))
        }
        log.warnP(s"$savedABookInfo already in PENDING state; overdue=$isOverdue")
        (isOverdue, savedABookInfo)
      }

      if (proceed) {
        abookImporter.asyncProcessContacts(userId, origin, updatedEntry, s3Key, WeakReference(json))
        log.infoP(s"scheduled for processing: $updatedEntry")
      }
      Some(updatedEntry)
    }
  }

  def hideEmailFromUser(userId: Id[User], email: EmailAddress): Boolean = {
    val result = db.readWrite {
      implicit session =>
        econtactRepo.hideEmailFromUser(userId, email)
    }
    econtactTypeahead.refresh(userId)
    log.info(s"[hideEmailFromUser($userId, $email)] res=$result")
    result
  }

  def getABookRawInfosDirect(userId: Id[User]): JsValue = {
    val abookInfos = db.readOnlyMaster(attempts = 2) {
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

  def internKifiContact(userId: Id[User], contact: BasicContact): EContact = {
    val kifiAbook = db.readWrite { implicit session =>  abookInfoRepo.internKifiABook(userId) }
    contactInterner.internContact(userId, kifiAbook.id.get, contact)
  }

  def validateAllContacts(readOnly: Boolean): Unit = {
    log.info("[EContact Validation] Starting validation of all EContacts.")
    val invalidContacts = mutable.Map[Id[EContact], String]()
    val fixableContacts = mutable.Map[Id[EContact], String]()
    val pageSize = 1000
    var lastPageSize = -1
    var nextPage = 0

    do {
      db.readWrite { implicit session =>
        val batch = econtactRepo.page(nextPage, pageSize, Set.empty)
        batch.foreach { contact =>
          EmailAddress.validate(contact.email.address) match {
            case Failure(invalidEmail) => {
              log.error(s"[EContact Validation] Found invalid email contact: ${contact.email}")
              invalidContacts += (contact.id.get -> contact.email.address)
              if (!readOnly) { econtactRepo.save(contact.copy(state = EContactStates.INACTIVE)) }
            }
            case Success(validEmail) => {
              if (validEmail != contact.email) {
                log.warn(s"[EContact Validation] Found fixable email contact: ${contact.email}")
                fixableContacts += (contact.id.get -> contact.email.address)
                if (!readOnly) { econtactRepo.save(contact.copy(email = validEmail)) }
              }
            }
          }
        }
        lastPageSize = batch.length
        nextPage += 1
      }
    } while (lastPageSize == pageSize)

    log.info("[EContact Validation] Done with EContact validation.")

    val title = s"Email Contact Validation Report: ReadOnly Mode = $readOnly. Invalid Contacts: ${invalidContacts.size}. Fixable Contacts: ${fixableContacts.size}"
    val msg = s"Invalid Contacts: \n\n ${invalidContacts.mkString("\n")} \n\n Fixable Contacts: \n\n ${fixableContacts.mkString("\n")}"
    shoebox.sendMail(ElectronicMail(from = SystemEmailAddress.ENG, to = List(SystemEmailAddress.ENG),
      subject = title, htmlBody = msg.replaceAll("\n","\n<br>"), category = NotificationCategory.System.ADMIN
    ))
  }

  def getContactNameByEmail(userId: Id[User], email: EmailAddress): Option[String] = {
    db.readOnlyMaster { implicit session =>
      econtactRepo.getByUserIdAndEmail(userId, email).collectFirst { case contact if contact.name.isDefined => contact.name.get }
    }
  }

  def getContactsByUser(userId: Id[User], page: Int = 0, pageSize: Option[Int] = None): Seq[EContact] = {
    val allContacts = db.readOnlyReplica { implicit session => econtactRepo.getByUserId(userId) }
    pageSize.collect { case size if page >= 0 => allContacts.sortBy(_.id.get.id).drop(page * size).take(size) } getOrElse allContacts
  }
}
