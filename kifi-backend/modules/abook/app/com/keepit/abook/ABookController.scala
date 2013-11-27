package com.keepit.abook

import com.google.inject.Inject
import com.keepit.common.db.slick.Database
import com.keepit.common.controller.{WebsiteController, ABookServiceController, ActionAuthenticator}
import com.keepit.model._
import com.keepit.common.db.{Id}
import play.api.mvc.{AsyncResult, Action}
import com.keepit.abook.store.{ABookRawInfoStore}
import scala.Some
import java.io.File
import scala.collection.{immutable, mutable}
import scala.io.Source
import scala.ref.WeakReference
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.ws.{WS, Response}
import scala.xml.PrettyPrinter
import scala.concurrent._
import scala.concurrent.duration._
import play.api.libs.functional.syntax._
import play.api.libs.json._
import play.api.Play
import play.api.Play.current
import scala.util.{Success, Failure}

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
  econtactRepo:EContactRepo,
  oauth2TokenRepo:OAuth2TokenRepo,
  abookCommander:ABookCommander,
  contactsUpdater:ContactsUpdaterPlugin
) extends WebsiteController(actionAuthenticator) with ABookServiceController {

  def importContactsP(userId:Id[User]) = Action(parse.json) { request =>
    val tokenOpt = request.body.asOpt[OAuth2Token]
    log.info(s"[importContactsP($userId)] tokenOpt=$tokenOpt")
    tokenOpt match {
      case None =>
        log.error(s"[importContactsP($userId)] token is invalid body=${request.body}")
        BadRequest("Invalid token")
      case Some(tk) => tk.issuer match {
        case OAuth2TokenIssuers.GOOGLE => {
          val savedToken = db.readWrite(attempts = 2) { implicit s =>
            oauth2TokenRepo.save(tk)
          }
          importGmailContacts(userId, tokenOpt.get.accessToken, Some(savedToken))
        }
        case _ => BadRequest(s"Unsupported issuer ${tk.issuer}")
      }
    }
  }

  def importContacts(userId:Id[User], provider:String, accessToken:String) = Action { request =>
    provider match {
      case "google" => {
        importGmailContacts(userId, accessToken, None)
      }
      case "facebook" => {
        if (Play.maybeApplication.isDefined && (!Play.isProd)) {
          val friendsUrl = "https://graph.facebook.com/me/friends"
          Async {
            WS.url(friendsUrl).withQueryString(("access_token", accessToken),("fields", "id,name,first_name,last_name,username,picture,email")).get map { resp =>
              resp.status match {
                case OK => {
                  val friends = resp.json
                  log.info(s"[facebook] friends:\n${Json.prettyPrint(friends)}")
                  Ok(friends)
                }
                case _ => {
                  BadRequest("Unsuccessful attempt to invoke facebook API")
                }
              }
            }
          }
        } else {
          BadRequest("Unsupported provider")
        }
      }
    }
  }

  def importGmailContacts(userId: Id[User], accessToken: String, tokenOpt:Option[OAuth2Token]): AsyncResult = {  // todo: move to commander
    val resF = importGmailContactsF(userId, accessToken, tokenOpt)
    Async {
      resF.map { abookInfoOpt =>
        abookInfoOpt match {
          case Some(info) => Ok(Json.toJson(info))
          case None => BadRequest("Failed to import gmail contacts")
        }
      }
    }
  }

  def importGmailContactsF(userId: Id[User],accessToken: String, tokenOpt:Option[OAuth2Token]):Future[Option[ABookInfo]] = {  // todo: move to commander
    val USER_INFO_URL = "https://www.googleapis.com/oauth2/v2/userinfo"
    val CONTACTS_URL = "https://www.google.com/m8/feeds/contacts/default/full" // TODO: paging (alt=json ignored)

    WS.url(USER_INFO_URL).withQueryString(("access_token", accessToken)).get flatMap {
      resp =>
        resp.status match {
          case OK => {
            val userInfoJson = resp.json
            val gUserInfo = userInfoJson.as[GmailABookOwnerInfo]
            log.info(s"[g-contacts] userInfoResp=${userInfoJson} googleUserInfo=${gUserInfo}")

            WS.url(CONTACTS_URL).withQueryString(("access_token", accessToken), ("max-results", Int.MaxValue.toString)).get map { contactsResp =>
              if (contactsResp.status == OK) {
                val contacts = contactsResp.xml // TODO: optimize; hand-off
                log.info(s"[g-contacts] $contacts")
                log.debug(new scala.xml.PrettyPrinter(300, 2).format(contacts))
                val jsArrays: immutable.Seq[JsArray] = (contacts \\ "feed").map {
                  feed =>
                    val gId = (feed \ "id").text
                    log.info(s"[g-contacts] id=$gId")
                    val entries: Seq[JsObject] = (feed \ "entry").map {
                      entry =>
                        val title = (entry \ "title").text
                        val emails = (entry \ "email").map(_ \ "@address")
                        log.info(s"[g-contacts] title=$title email=$emails")
                        Json.obj("name" -> title, "emails" -> Json.toJson(emails.seq.map(_.toString)))
                    }
                    JsArray(entries)
                }

                val abookUpload = Json.obj("origin" -> "gmail", "ownerId" -> gUserInfo.id, "numContacts" -> jsArrays(0).value.length, "contacts" -> jsArrays(0))
                log.info(Json.prettyPrint(abookUpload))
                val abookInfo = abookCommander.processUpload(userId, ABookOrigins.GMAIL, Some(gUserInfo), tokenOpt, abookUpload)
                Some(abookInfo)
              } else {
                log.error(s"Failed to retrieve gmail contacts") // todo: try later
                None
              }
            }
          }
          case _ =>
            log.error("Failed to obtain access token")
            future { None }
        }
    }
  }

  def upload(userId:Id[User], origin:ABookOriginType) = Action(parse.json(maxLength = 1024 * 50000)) { request =>
    val json : JsValue = request.body
    val abookRepoEntryF: Future[ABookInfo] = Future {
      abookCommander.processUpload(userId, origin, None, None, json)
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
    val abookInfoRepoEntry = abookCommander.processUpload(userId, origin, None, None, json)
    Ok(Json.toJson(abookInfoRepoEntry))
  }

  // direct JSON-upload (for testing only)
  def uploadJsonDirect(userId:Id[User], origin:ABookOriginType) = Action(parse.json(maxLength = 1024 * 50000)) { request =>
    val json = request.body
    log.info(s"[uploadJsonDirect($userId,$origin)] json=${Json.prettyPrint(json)}")
    val abookInfoRepoEntry = abookCommander.processUpload(userId, origin, None, None, json)
    Ok(Json.toJson(abookInfoRepoEntry))
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

}
