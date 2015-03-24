package com.keepit.abook.controllers

import com.google.inject.Inject
import com.keepit.common.db.slick.Database
import com.keepit.common.controller.{ ABookServiceController, UserActions, UserActionsHelper }
import com.keepit.model._
import com.keepit.common.db.{ ExternalId, Id }
import play.api.mvc.Action
import com.keepit.abook.store.ABookRawInfoStore
import scala.Some
import java.io.File
import scala.io.Source
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.functional.syntax._
import play.api.libs.json._
import com.keepit.common.logging.{ LogPrefix, Logging }
import com.keepit.abook.typeahead.EContactTypeahead
import com.keepit.typeahead.TypeaheadHit
import com.keepit.common.queue.RichConnectionUpdateMessage
import com.keepit.common.mail.{ BasicContact, EmailAddress }
import com.keepit.abook.model.{ EContactRepo, EContact }
import com.keepit.abook.{ ABookImporterPlugin, OAuth2TokenRepo, ABookInfoRepo }
import com.keepit.abook.commanders.{ LocalRichConnectionCommander, ABookCommander }

// provider-specific
class ABookOwnerInfo(val id: Option[String], val email: Option[String] = None)

object ABookOwnerInfo {
  def apply(id: Option[String], email: Option[String]) = new ABookOwnerInfo(id, email)
  def unapply(ownerInfo: ABookOwnerInfo): Option[(Option[String], Option[String])] = Some(ownerInfo.id, ownerInfo.email)
  val EMPTY = ABookOwnerInfo(None, None)
}

class GmailABookOwnerInfo(id: Option[String], email: Option[String]) extends ABookOwnerInfo(id, email) {
  override def toString = s"[GmailABookOwnerInfo: id=$id email=$email"
}

object GmailABookOwnerInfo {
  def apply(id: Option[String], email: Option[String]) = new GmailABookOwnerInfo(id, email)
  def unapply(userInfo: GmailABookOwnerInfo): Option[(Option[String], Option[String])] = Some(userInfo.id, userInfo.email)

  implicit val reads: Reads[GmailABookOwnerInfo] = (
    (__ \ 'sub).read[String] orElse (__ \ 'id).read[String] fmap (x => Option(x)) and
    (__ \ 'email).readNullable[String]
  )(GmailABookOwnerInfo.apply _)

  implicit val writes: Writes[GmailABookOwnerInfo] = (
    (__ \ 'id).writeNullable[String] and
    (__ \ 'email).writeNullable[String]
  )(unlift(GmailABookOwnerInfo.unapply))
  val EMPTY = GmailABookOwnerInfo(None, None)
}

import Logging._
class ABookController @Inject() (
    val userActionsHelper: UserActionsHelper,
    db: Database,
    s3: ABookRawInfoStore,
    abookInfoRepo: ABookInfoRepo,
    econtactRepo: EContactRepo,
    oauth2TokenRepo: OAuth2TokenRepo,
    typeahead: EContactTypeahead,
    abookCommander: ABookCommander,
    contactsUpdater: ABookImporterPlugin,
    richConnectionCommander: LocalRichConnectionCommander) extends UserActions with ABookServiceController {

  // gmail
  def importContacts(userId: Id[User]) = Action.async(parse.json) { request =>
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
            case t: Throwable =>
              BadRequest(Json.obj("code" -> s"Failed to import gmail contacts; exception:$t ;cause=${t.getCause}; stackTrace=${t.getStackTrace.mkString("", "\n", "\n")}"))
          }
        }
        case _ => resolve(BadRequest(Json.obj("code" -> s"Unsupported issuer ${tk.issuer}")))
      }
    }
  }

  // ios
  def uploadContacts(userId: Id[User], origin: ABookOriginType) = Action(parse.json(maxLength = 1024 * 50000)) { request =>
    abookCommander.processUpload(userId, origin, None, None, request.body) match {
      case Some(info) => Ok(Json.toJson(info))
      case None => BadRequest(Json.obj("code" -> "abook_empty_not_created"))
    }
  }

  // upload JSON file via form (for admin-page testing)
  def formUpload(userId: Id[User], origin: ABookOriginType = ABookOrigins.IOS) = Action(parse.multipartFormData) { request =>
    val jsonFilePart = request.body.file("abook_json")
    val jsonFile = File.createTempFile("abook_json", "json")
    jsonFilePart.getOrElse(throw new IllegalArgumentException("form field ios_json not found")).ref.moveTo(jsonFile, true)
    val jsonSrc = Source.fromFile(jsonFile)(io.Codec("UTF-8")).getLines.foldLeft("") { (a, c) => a + c }
    log.info(s"[formUpload($userId, $origin)] jsonFile=$jsonFile jsonSrc=$jsonSrc")
    val json = Json.parse(jsonSrc) // for testing
    log.info(s"[formUpload] json=${Json.prettyPrint(json)}")
    val abookInfoRepoEntryOpt = abookCommander.processUpload(userId, origin, None, None, json)
    Ok(Json.toJson(abookInfoRepoEntryOpt))
  }

  def hideEmailFromUser(userId: Id[User], email: EmailAddress) = Action { request =>
    Ok(JsBoolean(abookCommander.hideEmailFromUser(userId, email)))
  }

  def getABookRawInfos(userId: Id[User]) = Action { request =>
    val rawInfos = abookCommander.getABookRawInfosDirect(userId)
    Ok(rawInfos)
  }

  def getAllABookInfos() = Action { request =>
    val abookInfos = db.readOnlyReplica(attempts = 2) { implicit session =>
      abookInfoRepo.all()
    }
    Ok(Json.toJson(abookInfos))
  }

  def getPagedABookInfos(page: Int, size: Int) = Action { request =>
    val abookInfos = db.readOnlyReplica(attempts = 2) { implicit session =>
      abookInfoRepo.page(page, size)
    }
    Ok(Json.toJson(abookInfos))
  }

  def getABooksCount() = Action { request =>
    val count = db.readOnlyReplica(attempts = 2) { implicit session =>
      abookInfoRepo.count
    }
    Ok(JsNumber(count))
  }

  def getABookInfos(userId: Id[User]) = Action { request =>
    val abookInfos = db.readOnlyReplica(attempts = 2) { implicit session =>
      abookInfoRepo.findByUserId(userId)
    }
    Ok(Json.toJson(abookInfos))
  }

  def getABookInfo(userId: Id[User], id: Id[ABookInfo]) = Action { request =>
    val infoOpt = abookCommander.getABookInfo(userId, id)
    Ok(Json.toJson(infoOpt))
  }

  def getABookInfoByExternalId(externalId: ExternalId[ABookInfo]) = Action { request =>
    db.readOnlyMaster { implicit session =>
      Ok(Json.toJson(abookInfoRepo.getByExternalId(externalId)))
    }
  }

  // retrieve from S3
  def getContactsRawInfo(userId: Id[User], origin: ABookOriginType) = Action { request =>
    val abookInfos = {
      val abooks = db.readOnlyMaster(attempts = 2) { implicit session =>
        abookInfoRepo.findByUserIdAndOrigin(userId, origin)
      }
      abooks.map { abookInfo =>
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

  def getEContactCount(userId: Id[User]) = Action { request =>
    val count = db.readOnlyReplica { implicit s =>
      econtactRepo.countEmailContacts(userId, distinctEmailAccounts = false)
    }
    Ok(JsNumber(count))
  }

  def getOAuth2Token(userId: Id[User], abookId: Id[ABookInfo]) = Action { request =>
    log.info(s"[getOAuth2Token] userId=$userId, abookId=$abookId")
    val tokenOpt = db.readOnlyMaster(attempts = 2) { implicit s =>
      for {
        abookInfo <- abookInfoRepo.getById(abookId)
        oauth2TokenId <- abookInfo.oauth2TokenId
        oauth2Token <- oauth2TokenRepo.getById(oauth2TokenId)
      } yield oauth2Token
    }
    Ok(Json.toJson(tokenOpt))
  }

  def refreshPrefixFilter(userId: Id[User]) = Action.async { request =>
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

  def getContactNameByEmail(userId: Id[User]) = Action(parse.json) { request =>
    val email = request.body.as[EmailAddress]
    val name = abookCommander.getContactNameByEmail(userId, email)
    Ok(Json.toJson(name))
  }

  def internKifiContacts(userId: Id[User]) = Action(parse.json) { request =>
    val contacts = request.body.as[Seq[BasicContact]]
    log.info(s"[internKifiContacts] userId=$userId contacts=$contacts")

    val eContacts = abookCommander.internKifiContacts(userId, contacts)
    val richContacts = eContacts.map(EContact.toRichContact)
    Ok(Json.toJson(richContacts))
  }

  def prefixQuery(userId: Id[User], q: String, maxHits: Option[Int]) = Action.async { request =>
    implicit val ord = TypeaheadHit.defaultOrdering[EContact]
    typeahead.topN(userId, q, maxHits).map { econtactHits =>
      val hits = econtactHits.map { hit =>
        TypeaheadHit(hit.score, hit.name, hit.ordinal, EContact.toRichContact(hit.info))
      }
      Ok(Json.toJson(hits))
    }
  }

  def getContactsByUser(userId: Id[User], page: Int = 0, pageSize: Option[Int] = None) = Action { request =>
    val relevantContacts = abookCommander.getContactsByUser(userId, page, pageSize)
    val richContacts = relevantContacts.map(EContact.toRichContact)
    Ok(Json.toJson(richContacts))
  }

  def getUsersWithContact(email: EmailAddress) = Action { request =>
    val userIds = abookCommander.getUsersWithContact(email)
    Ok(Json.toJson(userIds))
  }

}
