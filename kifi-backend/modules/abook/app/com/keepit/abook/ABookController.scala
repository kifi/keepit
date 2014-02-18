package com.keepit.abook

import com.google.inject.Inject
import com.keepit.common.db.slick.Database
import com.keepit.common.controller.{WebsiteController, ABookServiceController, ActionAuthenticator}
import com.keepit.model._
import com.keepit.common.db.Id
import com.keepit.common.performance.timing
import play.api.mvc.Action
import com.keepit.abook.store.ABookRawInfoStore
import scala.Some
import java.io.File
import scala.collection.mutable
import scala.io.Source
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.functional.syntax._
import play.api.libs.json._
import scala.util.{Success, Failure}
import com.keepit.common.logging.{LogPrefix, Logging}
import com.keepit.abook.typeahead.EContactABookTypeahead
import com.keepit.typeahead.TypeaheadHit
import scala.concurrent.Future
import com.keepit.common.akka.SafeFuture
import java.text.Normalizer

// provider-specific
class ABookOwnerInfo(val id:Option[String], val email:Option[String] = None)

object ABookOwnerInfo {
  def apply(id:Option[String], email:Option[String]) = new ABookOwnerInfo(id, email)
  def unapply(ownerInfo:ABookOwnerInfo):Option[(Option[String],Option[String])] = Some(ownerInfo.id, ownerInfo.email)
  val EMPTY = ABookOwnerInfo(None, None)
}

class GmailABookOwnerInfo(id:Option[String], email:Option[String], /* val verified:Option[Boolean] = None, */ val hd:Option[String] = None) extends ABookOwnerInfo(id, email)

object GmailABookOwnerInfo {
  def apply(id:Option[String], email:Option[String], /* verified:Option[Boolean],*/ hd:Option[String]) = new GmailABookOwnerInfo(id, email, hd)
  def unapply(userInfo:GmailABookOwnerInfo):Option[(Option[String], Option[String], /* Option[Boolean],*/ Option[String])] = Some(userInfo.id, userInfo.email, /* userInfo.verified, */ userInfo.hd)

  implicit val format = (
    (__ \ 'id).formatNullable[String] and
    (__ \ 'email).formatNullable[String] and
//    (__ \ 'verified_email).formatNullable[Boolean] and
    (__ \ 'hd).formatNullable[String]
  )(GmailABookOwnerInfo.apply, unlift(GmailABookOwnerInfo.unapply))

  val EMPTY = GmailABookOwnerInfo(None, None, /* None, */ None)
}

import Logging._
class ABookController @Inject() (
  actionAuthenticator:ActionAuthenticator,
  db:Database,
  s3:ABookRawInfoStore,
  abookInfoRepo:ABookInfoRepo,
  contactRepo:ContactRepo,
  econtactRepo:EContactRepo,
  oauth2TokenRepo:OAuth2TokenRepo,
  typeahead:EContactABookTypeahead,
  abookCommander:ABookCommander,
  contactsUpdater:ContactsUpdaterPlugin
) extends WebsiteController(actionAuthenticator) with ABookServiceController {

  // gmail
  def importContacts(userId:Id[User]) = Action.async(parse.json) { request =>
    implicit val prefix = LogPrefix(s"importContacts($userId)")
    val tokenOpt = request.body.asOpt[OAuth2Token]
    log.infoP(s"tokenOpt=$tokenOpt")
    tokenOpt match {
      case None =>
        log.errorP(s"token is invalid body=${request.body}")
        resolve(BadRequest(Json.obj("code" -> s"Invalid token ${request.body}")))
      case Some(tk) => tk.issuer match {
        case OAuth2TokenIssuers.GOOGLE => {
          val saved = db.readWrite(attempts = 2) { implicit s =>
            oauth2TokenRepo.save(tk) // for future use
          }
          abookCommander.importGmailContacts(userId, tk.accessToken, Some(saved)) map { info =>
            Ok(Json.toJson(info))
          } recover {
            case t:Throwable =>
              BadRequest(Json.obj("code" -> s"Failed to import gmail contacts; exception:$t ;cause=${t.getCause}; stackTrace=${t.getStackTraceString}"))
          }
        }
        case _ => resolve(BadRequest(Json.obj("code" -> s"Unsupported issuer ${tk.issuer}")))
      }
    }
  }

  // ios
  def uploadContacts(userId:Id[User], origin:ABookOriginType) = Action(parse.json(maxLength = 1024 * 50000)) { request =>
    abookCommander.processUpload(userId, origin, None, None, request.body) match {
      case Some(info) => Ok(Json.toJson(info))
      case None => BadRequest(Json.obj("code" -> "abook_empty_not_created"))
    }
  }

  // upload JSON file via form (for admin-page testing)
  def formUpload(userId:Id[User], origin:ABookOriginType = ABookOrigins.IOS) = Action(parse.multipartFormData) { request =>
    val jsonFilePart = request.body.file("abook_json")
    val jsonFile = File.createTempFile("abook_json", "json")
    jsonFilePart.getOrElse(throw new IllegalArgumentException("form field ios_json not found")).ref.moveTo(jsonFile, true)
    val jsonSrc = Source.fromFile(jsonFile)(io.Codec("UTF-8")).getLines.foldLeft("") { (a,c) => a + c }
    log.info(s"[formUpload($userId, $origin)] jsonFile=$jsonFile jsonSrc=$jsonSrc")
    val json = Json.parse(jsonSrc) // for testing
    log.info(s"[formUpload] json=${Json.prettyPrint(json)}")
    val abookInfoRepoEntryOpt = abookCommander.processUpload(userId, origin, None, None, json)
    Ok(Json.toJson(abookInfoRepoEntryOpt))
  }

  def getContacts(userId:Id[User], maxRows:Int) = Action { request =>
    Ok(abookCommander.getContactsDirect(userId, maxRows))
  }

  def getEContactById(contactId:Id[EContact]) = Action { request =>
    // todo: parse email
    abookCommander.getEContactByIdDirect(contactId) match {
      case Some(js) => Ok(js)
      case _ => Ok(JsNull)
    }
  }

  def getEContactsByIds() = Action(parse.json) { request =>
    val jsArray = request.body.asOpt[JsArray] getOrElse JsArray()
    val contactIds = jsArray.value map { x => Id[EContact](x.as[Long]) }
    val contacts = db.readOnly { implicit ro =>
      econtactRepo.getByIds(contactIds)
    }
    Ok(Json.toJson[Seq[EContact]](contacts))
  }

  def getEContactByEmail(userId:Id[User], email:String) = Action { request =>
    // todo: parse email
    abookCommander.getEContactByEmailDirect(userId, email) match {
      case Some(js) => Ok(js)
      case _ => Ok(JsNull)
    }
  }


  def getEContacts(userId:Id[User], maxRows:Int) = Action { request =>
    val res = {
      abookCommander.getEContactsDirect(userId, maxRows)
    }
    Ok(res)
  }

  def getSimpleContactInfos(userId:Id[User], maxRows:Int) = Action { request =>
    val res = {
      val jsonBuilder = mutable.ArrayBuilder.make[JsValue]
      db.readOnly(attempts = 2) { implicit session =>
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
    Ok(res)
  }

  // cache
  def getMergedContactInfos(userId:Id[User], maxRows:Int) = Action { request =>
    val res = {
      val m = new mutable.HashMap[String, Set[String]]()
      val iter = db.readOnly(attempts = 2) { implicit session =>
        contactRepo.getByUserIdIter(userId, maxRows)
      }
      iter.foreach { c => // assume c.name exists (fix import)
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
      val jsonBuilder = mutable.ArrayBuilder.make[JsValue]
      m.keysIterator.foreach { k =>
        jsonBuilder += Json.obj("name" -> JsString(k), "emails" -> JsArray(m.get(k).getOrElse(Set.empty[String]).toSeq.map(JsString(_))))
      }
      val contacts = jsonBuilder.result
      JsArray(contacts)
    }
    Ok(res)
  }

  def getABookRawInfos(userId:Id[User]) = Action { request =>
    val rawInfos = abookCommander.getABookRawInfosDirect(userId)
    Ok(rawInfos)
  }

  def getAllABookInfos() = Action { request =>
    val abookInfos = db.readOnly(attempts = 2) { implicit session =>
      abookInfoRepo.all()
    }
    Ok(Json.toJson(abookInfos))
  }

  def getPagedABookInfos(page:Int, size:Int) = Action { request =>
    val abookInfos = db.readOnly(attempts = 2) { implicit session =>
      abookInfoRepo.page(page, size)
    }
    Ok(Json.toJson(abookInfos))
  }

  def getABooksCount() = Action { request =>
    val count = db.readOnly(attempts = 2) { implicit session =>
      abookInfoRepo.count
    }
    Ok(JsNumber(count))
  }

  def getABookInfos(userId:Id[User]) = Action { request =>
    val abookInfos = db.readOnly(attempts = 2) { implicit session =>
      abookInfoRepo.findByUserId(userId)
    }
    Ok(Json.toJson(abookInfos))
  }

  def getABookInfo(userId:Id[User], id:Id[ABookInfo]) = Action { request =>
    val infoOpt = abookCommander.getABookInfo(userId, id)
    Ok(Json.toJson(infoOpt))
  }

  // retrieve from S3
  def getContactsRawInfo(userId:Id[User],origin:ABookOriginType) = Action { request =>
    val abookInfos = {
      val abooks = db.readOnly(attempts = 2) { implicit session =>
        abookInfoRepo.findByUserIdAndOrigin(userId, origin)
      }
      abooks.map{ abookInfo =>
        val key = abookInfo.rawInfoLoc.getOrElse(
          origin match {
            case ABookOrigins.IOS => abookCommander.toS3Key(userId, origin, None) // only ok for IOS
            case _ => throw new IllegalStateException(s"[getContactsRawInfo($userId, $origin)] rawInfoLoc not set for $abookInfo")
          }
        )
        val stored = s3.get(key)
        log.info(s"userId=$userId origin=$origin stored=$stored")
        Json.toJson[ABookRawInfo](stored.getOrElse(ABookRawInfo.EMPTY))
      }
    }
    Ok(JsArray(abookInfos))
  }

  def getEContactCount(userId:Id[User]) = Action { request =>
    val count = db.readOnly(attempts = 2) { implicit s =>
      econtactRepo.getEContactCount(userId)
    }
    Ok(JsNumber(count))
  }

  def getOAuth2Token(userId:Id[User], abookId:Id[ABookInfo]) = Action { request =>
    log.info(s"[getOAuth2Token] userId=$userId, abookId=$abookId")
    val tokenOpt = db.readOnly(attempts = 2) { implicit s =>
      for {
        abookInfo <- abookInfoRepo.getById(abookId)
        oauth2TokenId <- abookInfo.oauth2TokenId
        oauth2Token <- oauth2TokenRepo.getById(oauth2TokenId)
      } yield oauth2Token
    }
    Ok(Json.toJson(tokenOpt))
  }

  def getOrCreateEContact(userId:Id[User], email:String, name:Option[String], firstName:Option[String], lastName:Option[String]) = Action { request =>
    log.info(s"[getOrCreateEContact] userId=$userId email=$email name=$name")
    abookCommander.getOrCreateEContact(userId, email, name, firstName, lastName) match {
      case Success(c) => Ok(Json.toJson(c))
      case Failure(t) => BadRequest(t.getMessage)
    }
  }

  // todo: removeme (inefficient)
  def queryEContacts(userId:Id[User], limit:Int, search: Option[String], after:Option[String]) = Action { request =>
    val eContacts = abookCommander.queryEContacts(userId, limit, search, after)
    log.info(s"[queryEContacts] userId=$userId search=$search after=$after limit=$limit res(len=${eContacts.length}):${eContacts.mkString}")
    Ok(Json.toJson(eContacts))
  }

  implicit val ord = TypeaheadHit.defaultOrdering[EContact]
  def prefixSearchDirect(userId:Id[User], query:String):Future[Seq[EContact]] = { // todo(ray): move to commander
    if (query.trim.length > 0) {
      val filterF = typeahead.getPrefixFilter(userId) match {
        case Some(filter) => Future.successful(filter)
        case None => typeahead.build(userId)
      }
      filterF map { filter =>
        val res = typeahead.search(userId, query) getOrElse Seq.empty[EContact]
        log.info(s"[prefixSearch($userId,$query)] res=${res.mkString(",")}")
        res
      }
    } else {
      SafeFuture {
        db.readOnly(attempts = 2) { implicit ro =>
          econtactRepo.getByUserId(userId)
        }
      }
    }
  }

  def prefixSearch(userId:Id[User], query:String) = Action.async { request =>
    prefixSearchDirect(userId, query) map { res =>
      Ok(Json.toJson(res))
    }
  }

}
