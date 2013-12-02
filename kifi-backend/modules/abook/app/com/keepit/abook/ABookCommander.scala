package com.keepit.abook

import com.google.inject.Inject
import com.keepit.common.db.slick.Database
import com.keepit.abook.store.ABookRawInfoStore
import com.keepit.common.db.Id
import com.keepit.model._
import play.api.libs.json.{JsArray, Json, JsValue}
import scala.ref.WeakReference
import com.keepit.common.logging.Logging
import scala.collection.mutable
import scala.concurrent._
import scala.concurrent.duration._
import com.keepit.common.time._
import com.keepit.common.db.slick._
import scala.util.{Try, Failure, Success}

class ABookCommander @Inject() (
  db:Database,
  s3:ABookRawInfoStore,
  abookInfoRepo:ABookInfoRepo,
  contactRepo:ContactRepo,
  econtactRepo:EContactRepo,
  contactsUpdater:ContactsUpdaterPlugin
) extends Logging {

  val overdueThreshold = sys.props.getOrElse("abook.upload.overdue.threshold", "10").toInt // minutes

  def toS3Key(userId:Id[User], origin:ABookOriginType, abookOwnerInfo:Option[ABookOwnerInfo]):String = {
    val k = s"${userId.id}_${origin.name}"
    val ownerId = for (abookOwner <- abookOwnerInfo; ownerId <- abookOwner.id) yield ownerId
    ownerId match {
      case Some(id) => s"${k}_${id}"
      case None => k
    }
  }

  def getABookInfo(userId:Id[User], id:Id[ABookInfo]):Option[ABookInfo] = {
    db.readOnly(attempts = 2) { implicit s =>
      abookInfoRepo.getByUserIdAndABookId(userId, id)
    }
  }


  def processUpload(userId: Id[User], origin: ABookOriginType, ownerInfoOpt:Option[ABookOwnerInfo], oauth2TokenOpt: Option[OAuth2Token], json: JsValue): ABookInfo = {
    val abookRawInfoRes = Json.fromJson[ABookRawInfo](json)
    val abookRawInfo = abookRawInfoRes.getOrElse(throw new Exception(s"Cannot parse ${json}"))

    val s3Key = toS3Key(userId, origin, ownerInfoOpt)
    s3 += (s3Key -> abookRawInfo)
    log.info(s"[upload($userId,$origin)] s3Key=$s3Key rawInfo=$abookRawInfo}")

    val numContacts = abookRawInfo.numContacts orElse {
      (json \ "contacts").asOpt[JsArray] map { _.value.length }
    }
    val savedABookInfo = db.readWrite(attempts = 2) { implicit session =>
      val (abookInfo, dbEntryOpt) = origin match {
        case ABookOrigins.IOS => { // no ownerInfo or numContacts -- revisit later
          val abookInfo = ABookInfo(userId = userId, origin = abookRawInfo.origin, rawInfoLoc = Some(s3Key), oauth2TokenId = oauth2TokenOpt.flatMap(_.id), numContacts = numContacts, state = ABookInfoStates.INACTIVE)
          val dbEntryOpt = {
            val s = abookInfoRepo.findByUserIdAndOrigin(userId, origin)
            if (s.isEmpty) None else Some(s(0))
          }
          (abookInfo, dbEntryOpt)
        }
        case ABookOrigins.GMAIL => {
          val ownerInfo = ownerInfoOpt.getOrElse(throw new IllegalArgumentException("Owner info not set for $userId and $origin"))
          val abookInfo = ABookInfo(userId = userId, origin = abookRawInfo.origin, ownerId = ownerInfo.id, ownerEmail = ownerInfo.email, rawInfoLoc = Some(s3Key), oauth2TokenId = oauth2TokenOpt.flatMap(_.id),  numContacts = numContacts, state = ABookInfoStates.INACTIVE)
          val dbEntryOpt = abookInfoRepo.findByUserIdOriginAndOwnerId(userId, origin, abookInfo.ownerId)
          (abookInfo, dbEntryOpt)
        }
      }
      val savedEntry = dbEntryOpt match {
        case Some(currEntry) => {
          log.info(s"[upload($userId,$origin)] current entry: $currEntry")
          abookInfoRepo.save(currEntry.withNumContacts(numContacts))
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
      val isOverdue = db.readOnly(attempts = 2) { implicit s =>
        abookInfoRepo.isOverdue(savedABookInfo.id.get, currentDateTime.minusMinutes(10))
      }
      log.warn(s"[upload($userId,$origin)] $savedABookInfo already in PENDING state; overdue=$isOverdue")
      (isOverdue, savedABookInfo)
    }

    if (proceed) {
      contactsUpdater.asyncProcessContacts(userId, origin, updatedEntry, s3Key, WeakReference(json))
      log.info(s"[upload($userId,$origin)] scheduled for processing: $updatedEntry")
    }
    updatedEntry
  }

  def getContactsDirect(userId: Id[User], maxRows: Int): JsArray = {
    val ts = System.currentTimeMillis
    val jsonBuilder = mutable.ArrayBuilder.make[JsValue]
    db.readOnly(attempts = 2) {
      implicit session =>
        contactRepo.getByUserIdIter(userId, maxRows).foreach {
          jsonBuilder += Json.toJson(_)
        } // TODO: paging & caching
    }
    val contacts = jsonBuilder.result
    log.info(s"[getContacts($userId, $maxRows)] # of contacts returned: ${contacts.length} time-lapsed: ${System.currentTimeMillis - ts}")
    JsArray(contacts)
  }

  def getEContactByIdDirect(contactId:Id[EContact]):Option[JsValue] = {
    val econtactOpt = db.readOnly(attempts = 2) { implicit s =>
      econtactRepo.getById(contactId)
    }
    log.info(s"[getEContactByIdDirect($contactId)] res=$econtactOpt")
    econtactOpt map { Json.toJson(_) }
  }

  def getEContactByEmailDirect(userId:Id[User], email:String):Option[JsValue] = {
    val econtactOpt = db.readOnly(attempts = 2) { implicit s =>
      econtactRepo.getByUserIdAndEmail(userId, email)
    }
    log.info(s"[getEContactDirect($userId,$email)] res=$econtactOpt")
    econtactOpt map { Json.toJson(_) }
  }

  def getEContactsDirect(userId: Id[User], maxRows: Int): JsArray = {
    val ts = System.currentTimeMillis
    val jsonBuilder = mutable.ArrayBuilder.make[JsValue]
    db.readOnly(attempts = 2) {
      implicit session =>
        econtactRepo.getByUserIdIter(userId, maxRows).foreach {
          jsonBuilder += Json.toJson(_)
        } // TODO: paging & caching
    }
    val contacts = jsonBuilder.result
    log.info(s"[getEContacts($userId, $maxRows)] # of contacts returned: ${contacts.length} time-lapsed: ${System.currentTimeMillis - ts}")
    JsArray(contacts)
  }

  def getABookRawInfosDirect(userId: Id[User]): JsValue = {
    val abookInfos = db.readOnly(attempts = 2) {
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

  def getOrCreateEContact(userId:Id[User], email:String, name:Option[String] = None, firstName:Option[String] = None, lastName:Option[String] = None):Try[EContact] = {
    log.info(s"[getOrCreateEContact] userId=$userId email=$email name=$name")
    db.readWrite(attempts = 2) { implicit s =>
      econtactRepo.getOrCreate(userId, email, name, firstName, lastName)
    }
  }

}
