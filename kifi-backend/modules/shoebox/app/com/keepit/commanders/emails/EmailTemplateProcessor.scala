package com.keepit.commanders.emails

import java.net.URLEncoder

import com.google.inject.{ Provider, ImplementedBy, Inject }
import com.keepit.commanders.{ Hashtags, PathCommander }
import com.keepit.commanders.emails.tips.EmailTipProvider
import com.keepit.common.akka.SafeFuture
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.{ LargeString, Id }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.mail.EmailAddress
import com.keepit.common.mail.template.Tag.tagRegex
import com.keepit.common.net.URI
import com.keepit.common.store.S3ImageStore
import com.keepit.common.util.DescriptionElements
import com.keepit.inject.FortyTwoConfig
import com.keepit.common.mail.template.{ helpers, EmailLayout, EmailTrackingParam, EmailToSend, TagWrapper, tags, EmailTip }
import com.keepit.common.mail.template.EmailLayout._
import com.keepit.common.mail.template.helpers.{ toHttpsUrl, fullName, baseUrl }
import com.keepit.model._
import com.keepit.social.BasicUser
import org.jsoup.Jsoup
import play.api.libs.json.{ Json, JsValue }
import play.twirl.api.Html
import org.jsoup.nodes.Element

import scala.collection.JavaConversions.asScalaIterator

import scala.concurrent.{ ExecutionContext, Future }

sealed trait EmailHtmlError
case class EmailHtmlMissingImgAlt(element: Element) extends EmailHtmlError

class EmailTemplateHtmlDecorator @Inject() () {
  def apply(html: String)(onError: EmailHtmlError => Unit): String = {
    val doc = Jsoup.parse(html)
    val imgElements = doc.getElementsByTag("img")
    val imgIter: Iterator[Element] = imgElements.iterator()

    imgIter.filterNot(_.hasAttr("alt")).foreach { imgEle =>
      imgEle.attr("alt", "")
      onError(EmailHtmlMissingImgAlt(imgEle))
    }

    // does not return the html-escaped code b/c URLs with certain HTML entities aren't redirected correctly by Sendgrid
    // TODO run html compression here
    html
  }
}

case class ProcessedEmailResult(
  toUser: Option[Id[User]],
  subject: String,
  fromName: Option[String],
  htmlBody: LargeString,
  textBody: Option[LargeString],
  includedTip: Option[EmailTip])

@ImplementedBy(classOf[EmailTemplateProcessorImpl])
trait EmailTemplateProcessor {
  def process(module: EmailToSend): Future[ProcessedEmailResult]
}

class EmailTemplateProcessorImpl @Inject() (
    db: Database,
    libraryRepo: LibraryRepo,
    userRepo: UserRepo,
    orgRepo: OrganizationRepo,
    uriRepo: NormalizedURIRepo,
    keepRepo: KeepRepo,
    s3ImageStore: S3ImageStore,
    libPathCommander: PathCommander,
    emailAddressRepo: UserEmailAddressRepo,
    config: FortyTwoConfig,
    htmlDecorator: EmailTemplateHtmlDecorator,
    emailTipProvider: Provider[EmailTipProvider],
    emailOptOutCommander: EmailOptOutCommander,
    implicit val defaultContext: ExecutionContext,
    implicit val publicIdConfig: PublicIdConfiguration,
    private val airbrake: AirbrakeNotifier) extends EmailTemplateProcessor with Logging {

  /* for the lack of a better name, this is just a trait that encapsulates
     the type of object(s) needed to replace a placeholder */
  sealed trait NeededObject

  object NothingNeeded extends NeededObject

  case class AvatarUrlNeeded(id: Id[User]) extends NeededObject

  case class UserNeeded(id: Id[User]) extends NeededObject

  case class OrganizationNeeded(id: Id[Organization]) extends NeededObject

  case class LibraryNeeded(id: Id[Library]) extends NeededObject

  case class KeepNeeded(id: Id[Keep]) extends NeededObject

  case class UriNeeded(id: Id[NormalizedURI]) extends NeededObject

  case class DataNeededResult(
    users: Map[Id[User], User],
    imageUrls: Map[Id[User], String],
    libraries: Map[Id[Library], Library],
    keeps: Map[Id[Keep], Keep],
    organizations: Map[Id[Organization], Organization],
    uris: Map[Id[NormalizedURI], NormalizedURI])

  def process(emailToSend: EmailToSend) = new SafeFuture[ProcessedEmailResult]({
    val tipHtmlF = emailTipProvider.get().getTipHtml(emailToSend)

    val templatesF = tipHtmlF.map { tipHtmlOpt => Seq(emailToSend.htmlTemplate) ++ tipHtmlOpt.map(_._2) }

    templatesF.flatMap[ProcessedEmailResult] { templates =>
      val (htmlBody, textBody) = loadLayout(emailToSend, templates)

      val personalEmailCategories = Seq(NotificationCategory.User.WELCOME)

      val viaKifiSuffix = if (personalEmailCategories contains emailToSend.category) "" else " (via Kifi)"

      val fromName = emailToSend.fromName.collect {
        case Left(userId) => s"${fullName(userId)}" + viaKifiSuffix
        case Right(fromNameStr) => fromNameStr
      }

      val needs: Set[NeededObject] = {
        val needsByHtml = getNeededObjects(htmlBody.body, emailToSend)
        val needsByText = textBody map (text => getNeededObjects(text.body, emailToSend)) getOrElse Set.empty
        val needsBySubject = getNeededObjects(emailToSend.subject, emailToSend)
        val needsByFromName = fromName.map { text => getNeededObjects(text, emailToSend) } getOrElse Set.empty
        needsByHtml ++ needsByText ++ needsBySubject ++ needsByFromName
      }

      val userIds = needs.collect { case UserNeeded(id) => id }
      val orgIds = needs.collect { case OrganizationNeeded(id) => id }
      val avatarUrlUserIds = needs.collect { case AvatarUrlNeeded(id) => id }
      val libraryIds = needs.collect { case LibraryNeeded(id) => id }
      val keepIds = needs.collect { case KeepNeeded(id) => id }
      val uriIds = needs.collect { case UriNeeded(id) => id }

      val userImageUrlsF = getUserImageUrls(avatarUrlUserIds.toSeq)

      // fetches all libraries to get the ownerId: Id[User] values and adds them to
      // the set of Id[User]s to load User models from
      val usersAndLibrariesF = getLibraries(libraryIds) flatMap { libraries =>
        val ownerIds = libraries.map { case (id, library) => library.ownerId }.toSet
        val allUserIds = userIds ++ ownerIds
        getUsers(allUserIds.toSeq) map { users => (users, libraries) }
      }

      val keepsF = getKeeps(keepIds)
      val orgsF = getOrganizations(orgIds)
      val urisF = getUris(uriIds)

      for {
        (users, libraries) <- usersAndLibrariesF
        userImageUrls <- userImageUrlsF
        tipHtmlOpt <- tipHtmlF
        keeps <- keepsF
        orgs <- orgsF
        uris <- urisF
      } yield {
        val input = DataNeededResult(users = users, organizations = orgs, imageUrls = userImageUrls, libraries = libraries, keeps = keeps, uris = uris)
        val includedTip = tipHtmlOpt.map(_._1)

        val decoratedHtml = htmlDecorator(evalTemplate(htmlBody.body, input, emailToSend, includedTip)) {
          case EmailHtmlMissingImgAlt(ele) =>
            val msg = s"img element missing @alt in ${emailToSend.category.category} email on image element $ele"
            airbrake.notify(msg)
            // additional information about the email to logs for debugging
            log.warn(s"""$msg on subject="${emailToSend.subject}" html element = $ele""")
            ()
        }

        ProcessedEmailResult(
          toUser = emailToSend.to.left.toOption,
          subject = evalTemplate(emailToSend.subject, input, emailToSend, includedTip),
          htmlBody = LargeString(decoratedHtml),
          textBody = textBody.map(text => LargeString(evalTemplate(text.body, input, emailToSend, includedTip))),
          fromName = fromName.map(text => evalTemplate(text, input, emailToSend, includedTip)),
          includedTip = includedTip
        )
      }
    }
  }, Some("Email template processor process process"))

  private def loadLayout(emailToSend: EmailToSend, templates: Seq[Html]): (Html, Option[Html]) = {
    val layoutOpt: Option[EmailLayout] = emailToSend.templateOptions.get("layout")

    layoutOpt match {
      case Some(CustomLayout) =>
        val textBodyOpt = emailToSend.textTemplate.map { text => views.html.email.layouts.customText(text, emailToSend) }
        (views.html.email.layouts.custom(templates.head, emailToSend), textBodyOpt)
      case _ =>
        val textBodyOpt = emailToSend.textTemplate.map { text => views.html.email.layouts.defaultText(Seq(text)) }
        (views.html.email.layouts.default(templates, emailToSend), textBodyOpt)
    }
  }

  private def evalTemplate(text: String, input: DataNeededResult, emailToSend: EmailToSend, emailTipOpt: Option[EmailTip]): String = try {
    tagRegex.replaceAllIn(text, { rMatch =>
      val tagWrapper = Json.parse(rMatch.group(1)).as[TagWrapper]
      val tagArgs = tagWrapper.args

      // only call if Id[User] is expected as the first argument
      @inline def userId = jsValueAsUserId(tagArgs(0))
      @inline def user = input.users(userId)
      @inline def basicUser = BasicUser.fromUser(user)

      // only call if Id[Library] is expected as the first argument
      @inline def libraryId = tagArgs(0).as[Id[Library]]
      @inline def library: Library = input.libraries(libraryId)
      @inline def libAuthToken: Option[String] = tagArgs(1).asOpt[String]

      // only call if Id[Organization] is expected as the first argument
      @inline def orgId = tagArgs(0).as[Id[Organization]]
      @inline def org: Organization = input.organizations(orgId)
      @inline def orgAuthToken: Option[String] = tagArgs(1).asOpt[String]

      @inline def keepId = tagArgs(0).as[Id[Keep]]
      @inline def keep: Keep = input.keeps(keepId)

      @inline def uriId = tagArgs(0).as[Id[NormalizedURI]]
      @inline def uri: NormalizedURI = input.uris(uriId)

      val resultString = tagWrapper.label match {
        case tags.textWithHashtag => DescriptionElements.formatAsHtml(Hashtags.format(tagArgs(0).as[String])).body
        case tags.firstName => basicUser.firstName
        case tags.lastName => basicUser.lastName
        case tags.fullName => basicUser.firstName + " " + basicUser.lastName
        case tags.avatarUrl => toHttpsUrl(input.imageUrls(userId))
        case tags.profileUrl => config.applicationBaseUrl + "/" + basicUser.username.value
        case tags.profileLink =>
          val data = Json.obj("t" -> "us", "uid" -> basicUser.externalId)
          config.applicationBaseUrl + "/redir?data=" + URLEncoder.encode(Json.stringify(data), "ascii")

        case tags.discussionLink =>
          import com.keepit.common.core._
          val keepPubId = tagArgs(1)
          val accessTokenOpt = tagArgs(2).asOpt[String]
          val shouldDeepLink = tagArgs(3).as[Boolean]
          if (shouldDeepLink) {
            val data = Json.obj("t" -> "m", "uri" -> uri.externalId, "id" -> keepPubId, "at" -> accessTokenOpt).nonNullFields
            config.applicationBaseUrl + "/redir?data=" + URLEncoder.encode(Json.stringify(data), "ascii")
          } else uri.url + "/?"

        case tags.organizationId => Organization.publicId(org.id.get).id
        case tags.organizationName => org.name
        case tags.organizationLink =>
          val data = Json.obj("t" -> "oi", "oid" -> Organization.publicId(org.id.get).id, "at" -> orgAuthToken)
          config.applicationBaseUrl + "/redir?data=" + URLEncoder.encode(Json.stringify(data), "ascii")

        case tags.libraryUrl =>
          config.applicationBaseUrl + libPathCommander.getPathForLibrary(library)
        case tags.libraryLink =>
          val data = Json.obj("t" -> "lv", "lid" -> Library.publicId(library.id.get).id, "at" -> libAuthToken)
          val ans = config.applicationBaseUrl + "/redir?data=" + URLEncoder.encode(Json.stringify(data), "ascii")
          ans
        case tags.libraryName => library.name
        case tags.libraryId => Library.publicId(library.id.get).id
        case tags.libraryOwnerFullName =>
          val libOwner = input.users(library.ownerId)
          libOwner.fullName

        case tags.keepName => keep.title.getOrElse("Untitled Keep")
        case tags.keepUrl => keep.url
        case tags.unsubscribeUrl =>
          getUnsubUrl(emailToSend.to match {
            case Left(userId) => db.readOnlyReplica { implicit s => emailAddressRepo.getByUser(userId) }
            case Right(address) => address
          })
        case tags.unsubscribeUserUrl =>
          val address = db.readOnlyReplica { implicit s => emailAddressRepo.getByUser(userId) }
          getUnsubUrl(address)
        case tags.unsubscribeEmailUrl => getUnsubUrl(tagWrapper.args(0).as[EmailAddress])
        case tags.userExternalId => user.externalId.id
        case tags.title => emailToSend.title
        case tags.baseUrl => config.applicationBaseUrl
        case tags.kcid => emailToSend.urlKcidParam
        case tags.campaign => emailToSend.urlCampaignParam
        case tags.channel => emailToSend.urlChannelParam
        case tags.source => emailToSend.urlSourceParam
        case tags.footerHtml => evalTemplate(views.html.email.layouts.footer().body, input, emailToSend, None)
        case tags.trackingParam =>
          EmailTrackingParam(
            subAction = Json.fromJson[String](tagArgs(0)).asOpt,
            variableComponents = Seq.empty, // todo(josh) this needs to be passed in EmailToSend
            tip = emailTipOpt,
            auxiliaryData = emailToSend.auxiliaryData
          ).encode
      }
      resultString.replace("$", "\\$")
    })
  } catch {
    case ex: IndexOutOfBoundsException => log.error(s"[EmailTemplate] IOOB Exception. Text: $text"); throw ex
  }

  // used to gather the types of objects we need to replace the tags with real values
  private def getNeededObjects(text: String, emailToSend: EmailToSend): Set[NeededObject] = {
    tagRegex.findAllMatchIn(text).map[NeededObject] { rMatch =>
      val tagWrapper = Json.parse(rMatch.group(1)).as[TagWrapper]
      val tagArgs = tagWrapper.args

      // only call if Id[User] is expected as the first argument
      @inline def userId = jsValueAsUserId(tagArgs(0))

      @inline def keepId = tagArgs(0).as[Id[Keep]]

      tagWrapper.label match {
        case tags.discussionLink =>
          val uriId = tagArgs(0).as[Id[NormalizedURI]]
          UriNeeded(uriId)
        case tags.firstName | tags.lastName | tags.fullName | tags.profileUrl |
          tags.unsubscribeUserUrl | tags.userExternalId => UserNeeded(userId)
        case tags.avatarUrl => AvatarUrlNeeded(userId)
        case tags.organizationId | tags.organizationLink | tags.organizationName =>
          val orgId = tagArgs(0).as[Id[Organization]]
          OrganizationNeeded(orgId)
        case tags.libraryName | tags.libraryUrl | tags.libraryLink | tags.libraryOwnerFullName =>
          val libId = tagArgs(0).as[Id[Library]]
          LibraryNeeded(libId)
        case tags.keepName | tags.keepUrl =>
          KeepNeeded(keepId)
        case _ => NothingNeeded
      }
    }.toSet
  }

  private def getUsers(userIds: Seq[Id[User]]) = {
    db.readOnlyMasterAsync { implicit s => userRepo.getUsers(userIds) }
  }

  private def getLibraries(libraryIds: Set[Id[Library]]): Future[Map[Id[Library], Library]] =
    db.readOnlyMasterAsync { implicit s =>
      libraryIds.map(id => id -> libraryRepo.get(id)).toMap
    }

  private def getKeeps(keepIds: Set[Id[Keep]]): Future[Map[Id[Keep], Keep]] = {
    db.readOnlyMasterAsync { implicit s =>
      keepIds.map(id => id -> keepRepo.get(id)).toMap
    }
  }

  private def getOrganizations(orgIds: Set[Id[Organization]]): Future[Map[Id[Organization], Organization]] = {
    db.readOnlyMasterAsync { implicit s =>
      orgIds.map(id => id -> orgRepo.get(id)).toMap
    }
  }

  private def getUris(uriIds: Set[Id[NormalizedURI]]): Future[Map[Id[NormalizedURI], NormalizedURI]] = {
    db.readOnlyMasterAsync { implicit s =>
      uriIds.map(id => id -> uriRepo.get(id)).toMap
    }
  }

  private def getUserImageUrls(userIds: Seq[Id[User]], width: Int = 100) = {
    Future.sequence(
      userIds map (userId => s3ImageStore.getPictureUrl(width, userId).map(userId -> _))
    ) map (_.toMap)
  }

  private def getUnsubUrl(emailAddr: EmailAddress) = {
    val token = emailOptOutCommander.generateOptOutToken(emailAddr)
    s"https://www.kifi.com${com.keepit.controllers.website.routes.EmailOptOutController.optOut(token)}"
  }

  private def jsValueAsUserId(arg: JsValue) = arg.as[Id[User]]
}

