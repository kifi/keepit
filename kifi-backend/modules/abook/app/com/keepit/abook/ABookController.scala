package com.keepit.abook

import com.google.inject.Inject
import com.keepit.common.db.slick.Database
import com.keepit.common.controller.{WebsiteController, ABookServiceController, ActionAuthenticator}
import com.keepit.model._
import com.keepit.common.db.{ExternalId, Id}
import com.keepit.common.performance.timing
import play.api.mvc.{AnyContent, Action}
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
import com.keepit.typeahead.{PrefixFilter, TypeaheadHit}
import scala.concurrent.Future
import com.keepit.common.akka.SafeFuture
import com.keepit.common.queue.RichConnectionUpdateMessage
import java.text.Normalizer
import scala.collection.mutable.ArrayBuffer
import com.keepit.commanders.LocalRichConnectionCommander
import com.keepit.common.mail.EmailAddress

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
  contactsUpdater:ContactsUpdaterPlugin,
  richConnectionCommander: LocalRichConnectionCommander
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

  def getEContactsByIds() = Action(parse.tolerantJson) { request =>
    val jsArray = request.body.asOpt[JsArray] getOrElse JsArray()
    val contactIds = jsArray.value map { x => Id[EContact](x.as[Long]) }
    val contacts = db.readOnly { implicit ro =>
      econtactRepo.getByIds(contactIds)
    }
    Ok(Json.toJson[Seq[EContact]](contacts))
  }

  def getEContactByEmail(userId:Id[User], email: EmailAddress) = Action { request =>
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

  def getABookInfoByExternalId(externalId: ExternalId[ABookInfo]) = Action { request =>
    db.readOnly { implicit session =>
      Ok(Json.toJson(abookInfoRepo.getByExternalId(externalId)))
    }
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

  def getOrCreateEContact(userId:Id[User], email: EmailAddress, name:Option[String], firstName:Option[String], lastName:Option[String]) = Action { request =>
    log.info(s"[getOrCreateEContact] userId=$userId email=$email name=$name")

    abookCommander.getOrCreateEContact(userId, BasicContact(email, name, firstName, lastName)) match {
      case Success(c) => Ok(Json.toJson(c))
      case Failure(t) => BadRequest(t.getMessage)
    }
  }

  // todo(ray): move to commander
  def prefixQueryDirect(userId:Id[User], limit:Int, search: Option[String], after:Option[String]): Seq[EContact] = timing(s"prefixQueryDirect($userId,$limit,$search,$after)") {
    @inline def mkId(email: EmailAddress) = s"email/${email.address}"
    val contacts = db.readOnly(attempts = 2) { implicit s =>
      econtactRepo.getByUserId(userId)
    }
    val filtered = search match {
      case Some(query) if query.trim.length > 0 => prefixSearchDirect(userId, query)
      case _ => contacts
    }
    val paged = after match {
      case Some(a) if a.trim.length > 0 => filtered.dropWhile(e => (mkId(e.email) != a)) match { // todo: revisit Option param handling
        case hd +: tl => tl
        case tl => tl
      }
      case _ => filtered
    }
    val eContacts = paged.take(limit)
    log.info(s"[queryEContacts(id=$userId, limit=$limit, search=$search after=$after)] res(len=${eContacts.length}):${eContacts.mkString.take(200)}")
    eContacts
  }
  def prefixQuery(userId:Id[User], limit:Int, search:Option[String], after:Option[String]) = Action { request =>
    val eContacts = prefixQueryDirect(userId, limit, search, after)
    Ok(Json.toJson(eContacts))
  }

  // todo: removeme (inefficient)
  def queryEContacts(userId:Id[User], limit:Int, search: Option[String], after:Option[String]) = Action { request =>
    val eContacts = abookCommander.queryEContacts(userId, limit, search, after)
    log.info(s"[queryEContacts] userId=$userId search=$search after=$after limit=$limit res(len=${eContacts.length}):${eContacts.mkString}")
    Ok(Json.toJson(eContacts))
  }

  implicit val ord = TypeaheadHit.defaultOrdering[EContact]
  def prefixSearchDirect(userId:Id[User], query:String):Seq[EContact] = { // todo(ray): move to commander
    if (query.trim.length > 0) {
      typeahead.search(userId, query) getOrElse Seq.empty[EContact]
    } else {
      db.readOnly(attempts = 2) { implicit ro =>
        econtactRepo.getByUserId(userId)
      }
    }
  }

  def prefixSearch(userId:Id[User], query:String) = Action { request =>
    val res = prefixSearchDirect(userId, query)
    Ok(Json.toJson(res))
  }

  def refreshPrefixFilter(userId:Id[User]) = Action.async { request =>
    typeahead.refresh(userId) map { filter =>
      log.info(s"[refreshPrefixFilter($userId)] updated; filter=$filter")
      Ok(Json.obj("code" -> "success"))
    }
  }

  def refreshPrefixFiltersByIds() = Action.async(parse.json) { request =>
    val jsArray = request.body.asOpt[JsArray] getOrElse JsArray()
    val userIds = jsArray.value map { x => Id[User](x.as[Long]) }
    log.info(s"[refreshPrefixFiltersByIds] ids(len=${userIds.length});${userIds.take(50).mkString(",")}")
    typeahead.refreshByIds(userIds) map { r =>
      Ok(Json.obj("code" -> "success"))
    }
  }

  def refreshAllPrefixFilters() = Action.async { request =>
    typeahead.refreshAll map { r =>
      Ok(Json.obj("code" -> "success"))
    }
  }

  def richConnectionUpdate() = Action(parse.tolerantJson) { request =>
    val updateMessage = request.body.as[RichConnectionUpdateMessage]
    richConnectionCommander.processUpdateImmediate(updateMessage)
    Ok("")
  }

}
