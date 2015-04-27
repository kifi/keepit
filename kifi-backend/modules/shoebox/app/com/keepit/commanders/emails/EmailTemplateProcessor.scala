package com.keepit.commanders.emails

import com.google.inject.{ Provider, ImplementedBy, Inject }
import com.keepit.commanders.UserCommander
import com.keepit.commanders.emails.tips.EmailTipProvider
import com.keepit.common.akka.SafeFuture
import com.keepit.common.db.{ LargeString, Id }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.mail.EmailAddress
import com.keepit.common.mail.template.Tag.tagRegex
import com.keepit.inject.FortyTwoConfig
import com.keepit.common.mail.template.{ helpers, EmailLayout, EmailTrackingParam, EmailToSend, TagWrapper, tags, EmailTip }
import com.keepit.common.mail.template.EmailLayout._
import com.keepit.common.mail.template.helpers.{ toHttpsUrl, fullName, baseUrl }
import com.keepit.model.{ Library, LibraryRepo, UserEmailAddressRepo, UserRepo, User }
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
    userCommander: Provider[UserCommander],
    emailAddressRepo: UserEmailAddressRepo,
    config: FortyTwoConfig,
    htmlDecorator: EmailTemplateHtmlDecorator,
    emailTipProvider: Provider[EmailTipProvider],
    emailOptOutCommander: EmailOptOutCommander,
    implicit val defaultContext: ExecutionContext,
    private val airbrake: AirbrakeNotifier) extends EmailTemplateProcessor with Logging {

  /* for the lack of a better name, this is just a trait that encapsulates
     the type of object(s) needed to replace a placeholder */
  sealed trait NeededObject

  object NothingNeeded extends NeededObject

  case class AvatarUrlNeeded(id: Id[User]) extends NeededObject

  case class UserNeeded(id: Id[User]) extends NeededObject

  case class LibraryNeeded(id: Id[Library]) extends NeededObject

  case class DataNeededResult(users: Map[Id[User], User], imageUrls: Map[Id[User], String],
    libraries: Map[Id[Library], Library])

  def process(emailToSend: EmailToSend) = new SafeFuture[ProcessedEmailResult]({
    val tipHtmlF = emailTipProvider.get().getTipHtml(emailToSend)

    val templatesF = tipHtmlF.map { tipHtmlOpt => Seq(emailToSend.htmlTemplate) ++ tipHtmlOpt.map(_._2) }

    templatesF.flatMap[ProcessedEmailResult] { templates =>
      val (htmlBody, textBody) = loadLayout(emailToSend, templates)

      val fromName = emailToSend.fromName.collect {
        case Left(userId) => s"${fullName(userId)} (via Kifi)"
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
      val avatarUrlUserIds = needs.collect { case AvatarUrlNeeded(id) => id }
      val libraryIds = needs.collect { case LibraryNeeded(id) => id }

      val userImageUrlsF = getUserImageUrls(avatarUrlUserIds.toSeq)

      // fetches all libraries to get the ownerId: Id[User] values and adds them to
      // the set of Id[User]s to load User models from
      val usersAndLibrariesF = getLibraries(libraryIds) flatMap { libraries =>
        val ownerIds = libraries.map { case (id, library) => library.ownerId }.toSet
        val allUserIds = userIds ++ ownerIds
        getUsers(allUserIds.toSeq) map { users => (users, libraries) }
      }

      for {
        (users, libraries) <- usersAndLibrariesF
        userImageUrls <- userImageUrlsF
        tipHtmlOpt <- tipHtmlF
      } yield {
        val input = DataNeededResult(users = users, imageUrls = userImageUrls, libraries = libraries)
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

  private def evalTemplate(text: String, input: DataNeededResult, emailToSend: EmailToSend, emailTipOpt: Option[EmailTip]): String = {
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

      tagWrapper.label match {
        case tags.firstName => basicUser.firstName
        case tags.lastName => basicUser.lastName
        case tags.fullName => basicUser.firstName + " " + basicUser.lastName
        case tags.avatarUrl => toHttpsUrl(input.imageUrls(userId))
        case tags.profileUrl => config.applicationBaseUrl + "/" + basicUser.username.value
        case tags.libraryUrl =>
          val libOwner = input.users(library.ownerId)
          config.applicationBaseUrl + Library.formatLibraryPath(libOwner.username, library.slug)
        case tags.libraryName => library.name
        case tags.libraryOwnerFullName =>
          val libOwner = input.users(library.ownerId)
          libOwner.fullName
        case tags.unsubscribeUrl =>
          getUnsubUrl(emailToSend.to match {
            case Left(userId) => db.readOnlyReplica { implicit s => emailAddressRepo.getByUser(userId) }
            case Right(address) => address
          })
        case tags.unsubscribeUserUrl =>
          val address = db.readOnlyReplica { implicit s => emailAddressRepo.getByUser(userId) }
          getUnsubUrl(address)
        case tags.unsubscribeEmailUrl => getUnsubUrl(tagWrapper.args(0).as[EmailAddress])
        case tags.userExternalId => user.externalId.toString()
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
    })
  }

  // used to gather the types of objects we need to replace the tags with real values
  private def getNeededObjects(text: String, emailToSend: EmailToSend): Set[NeededObject] = {
    tagRegex.findAllMatchIn(text).map[NeededObject] { rMatch =>
      val tagWrapper = Json.parse(rMatch.group(1)).as[TagWrapper]
      val tagArgs = tagWrapper.args

      // only call if Id[User] is expected as the first argument
      @inline def userId = jsValueAsUserId(tagArgs(0))

      tagWrapper.label match {
        case tags.firstName | tags.lastName | tags.fullName | tags.profileUrl |
          tags.unsubscribeUserUrl | tags.userExternalId => UserNeeded(userId)
        case tags.avatarUrl => AvatarUrlNeeded(userId)
        case tags.libraryName | tags.libraryUrl | tags.libraryOwnerFullName =>
          val libId = tagArgs(0).as[Id[Library]]
          LibraryNeeded(libId)
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

  private def getUserImageUrls(userIds: Seq[Id[User]], width: Int = 100) = {
    Future.sequence(
      userIds map (userId => userCommander.get.getUserImageUrl(userId, width).map(url => (userId, url)))
    ) map (_.toMap)
  }

  private def getUnsubUrl(emailAddr: EmailAddress) = {
    val token = emailOptOutCommander.generateOptOutToken(emailAddr)
    s"https://www.kifi.com${com.keepit.controllers.website.routes.EmailOptOutController.optOut(token)}"
  }

  private def jsValueAsUserId(arg: JsValue) = arg.as[Id[User]]
}

