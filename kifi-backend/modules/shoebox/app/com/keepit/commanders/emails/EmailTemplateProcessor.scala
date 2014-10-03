package com.keepit.commanders.emails

import com.google.inject.{ Provider, ImplementedBy, Inject }
import com.keepit.commanders.UserCommander
import com.keepit.commanders.emails.tips.EmailTipProvider
import com.keepit.common.db.{ LargeString, Id }
import com.keepit.common.db.slick.Database
import com.keepit.common.mail.EmailAddress
import com.keepit.common.mail.template.Tag.tagRegex
import com.keepit.heimdal.ContextData
import com.keepit.inject.FortyTwoConfig
import com.keepit.common.mail.template.{ EmailLayout, EmailTrackingParam, EmailToSend, TagWrapper, tags, EmailTip }
import com.keepit.common.mail.template.EmailLayout._
import com.keepit.common.mail.template.helpers.{ toHttpsUrl, fullName }
import com.keepit.model.{ NotificationCategory, UserEmailAddressRepo, UserRepo, User }
import com.keepit.social.BasicUser
import play.api.libs.json.{ Json, JsValue }
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.twirl.api.Html

import scala.concurrent.Future

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
    userRepo: UserRepo,
    userCommander: Provider[UserCommander],
    emailAddressRepo: UserEmailAddressRepo,
    config: FortyTwoConfig,
    emailTipProvider: Provider[EmailTipProvider],
    emailOptOutCommander: EmailOptOutCommander) extends EmailTemplateProcessor {

  /* for the lack of a better name, this is just a trait that encapsulates
     the type of object(s) needed to replace a placeholder */
  trait NeededObject

  object NothingNeeded extends NeededObject

  case class AvatarUrlNeeded(id: Id[User]) extends NeededObject

  case class UserNeeded(id: Id[User]) extends NeededObject

  case class DataNeededResult(users: Map[Id[User], User], imageUrls: Map[Id[User], String])

  def process(emailToSend: EmailToSend) = {
    val tipHtmlF = emailTipProvider.get().getTipHtml(emailToSend)

    val templatesF = tipHtmlF.map { tipHtmlOpt => Seq(emailToSend.htmlTemplate) ++ tipHtmlOpt.map(_._2) }

    templatesF.flatMap[ProcessedEmailResult] { templates =>
      val (htmlBody, textBody) = loadLayout(emailToSend, templates)

      val fromName = emailToSend.fromName.collect {
        case Left(userId) => s"${fullName(userId)} (via Kifi)"
        case Right(fromNameStr) => fromNameStr
      }

      val needs = getNeededObjects(htmlBody.body, emailToSend) ++
        textBody.map { text => getNeededObjects(text.body, emailToSend) }.getOrElse(Set.empty) ++
        getNeededObjects(emailToSend.subject, emailToSend) ++
        fromName.map { text => getNeededObjects(text, emailToSend) }

      val userIds = needs.collect { case UserNeeded(id) => id }
      val avatarUrlUserIds = needs.collect { case AvatarUrlNeeded(id) => id }

      val usersF = getUsers(userIds.toSeq)
      val userImageUrlsF = getUserImageUrls(avatarUrlUserIds.toSeq)

      for {
        users <- usersF
        userImageUrls <- userImageUrlsF
        tipHtmlOpt <- tipHtmlF
      } yield {
        val input = DataNeededResult(users = users, imageUrls = userImageUrls)
        val includedTip = tipHtmlOpt.map(_._1)
        ProcessedEmailResult(
          toUser = emailToSend.to.left.toOption,
          subject = evalTemplate(emailToSend.subject, input, emailToSend, includedTip),
          htmlBody = LargeString(evalTemplate(htmlBody.body, input, emailToSend, includedTip)),
          textBody = textBody.map(text => LargeString(evalTemplate(text.body, input, emailToSend, includedTip))),
          fromName = fromName.map(text => evalTemplate(text, input, emailToSend, includedTip)),
          includedTip = includedTip
        )
      }
    }
  }

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

      tagWrapper.label match {
        case tags.firstName => basicUser.firstName
        case tags.lastName => basicUser.lastName
        case tags.fullName => basicUser.firstName + " " + basicUser.lastName
        case tags.avatarUrl => toHttpsUrl(input.imageUrls(userId))
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
        case tags.campaign => emailToSend.campaign.getOrElse {
          // converts underscored_categories_like_this to camelCaseCategoryNames
          emailToSend.category.category.toLowerCase.split("_") match { case Array(h, q @ _*) => h + q.map(_.capitalize).mkString }
        }
        case tags.parentCategory => NotificationCategory.ParentCategory.get(emailToSend.category).getOrElse("unknown")
        case tags.footerHtml => evalTemplate(views.html.email.layouts.footer().body, input, emailToSend, None)
        case tags.trackingParam =>
          EmailTrackingParam(
            subAction = Json.fromJson[String](tagArgs(0)).asOpt,
            variableComponents = Seq.empty, // todo(josh) this needs to be passed in EmailToSend
            tip = emailTipOpt,
            auxiliaryData = None // todo(josh) this needs to either be set individually for each link in the template or passed in EmailToSend
          ).encode
      }
    })
  }

  // used to gather the types of objects we need to replace the tags with real values
  private def getNeededObjects(text: String, emailToSend: EmailToSend) = {
    tagRegex.findAllMatchIn(text).map[NeededObject] { rMatch =>
      val tagWrapper = Json.parse(rMatch.group(1)).as[TagWrapper]
      val tagArgs = tagWrapper.args

      // only call if Id[User] is expected as the first argument
      @inline def userId = jsValueAsUserId(tagArgs(0))

      tagWrapper.label match {
        case tags.firstName | tags.lastName | tags.fullName |
          tags.unsubscribeUserUrl | tags.userExternalId => UserNeeded(userId)
        case tags.avatarUrl => AvatarUrlNeeded(userId)
        case _ => NothingNeeded
      }
    }.toSet
  }

  private def getUsers(userIds: Seq[Id[User]]) = {
    db.readOnlyMasterAsync { implicit s => userRepo.getUsers(userIds) }
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

