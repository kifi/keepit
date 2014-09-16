package com.keepit.commanders.emails

import com.google.inject.{ ImplementedBy, Inject }
import com.keepit.commanders.UserCommander
import com.keepit.common.concurrent.FutureHelpers
import com.keepit.common.db.{ LargeString, Id }
import com.keepit.common.db.slick.Database
import com.keepit.common.mail.EmailAddress
import com.keepit.inject.FortyTwoConfig
import com.keepit.common.mail.template.{ TipTemplate, EmailToSend, TagWrapper, tags, EmailTips }
import com.keepit.common.mail.template.helpers.toHttpsUrl
import com.keepit.common.mail.template.Tag._
import com.keepit.model.{ UserEmailAddressRepo, UserRepo, User }
import com.keepit.social.BasicUser
import play.api.libs.json.{ Json, JsValue }
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.twirl.api.Html

import scala.concurrent.Future

case class ProcessedEmailResult(
  subject: String,
  htmlBody: LargeString,
  textBody: Option[LargeString])

@ImplementedBy(classOf[EmailTemplateProcessorImpl])
trait EmailTemplateProcessor {
  def process(module: EmailToSend): Future[ProcessedEmailResult]
}

class EmailTemplateProcessorImpl @Inject() (
    db: Database, userRepo: UserRepo,
    userCommander: UserCommander,
    emailAddressRepo: UserEmailAddressRepo,
    config: FortyTwoConfig,
    peopleRecommendationsTip: FriendRecommendationsEmailTip,
    emailOptOutCommander: EmailOptOutCommander) extends EmailTemplateProcessor {

  /* for the lack of a better name, this is just a trait that encapsulates
     the type of object(s) needed to replace a placeholder */
  trait NeededObject

  object NothingNeeded extends NeededObject

  case class AvatarUrlNeeded(id: Id[User]) extends NeededObject

  case class UserNeeded(id: Id[User]) extends NeededObject

  case class DataNeededResult(users: Map[Id[User], User], imageUrls: Map[Id[User], String])

  def process(emailToSend: EmailToSend) = {
    val tipHtmlF = getTipHtml(emailToSend)

    val templatesF = tipHtmlF.map { tipHtmlOpt => Seq(emailToSend.htmlTemplate) ++ tipHtmlOpt }

    templatesF.flatMap[ProcessedEmailResult] { templates =>
      val html = views.html.email.black.layout(templates)

      val needs = getNeededObjects(html.body, emailToSend) ++
        emailToSend.textTemplate.map { text => getNeededObjects(text.body, emailToSend) }.getOrElse(Set.empty) ++
        getNeededObjects(emailToSend.subject, emailToSend)

      val userIds = needs.collect { case UserNeeded(id) => id }
      val avatarUrlUserIds = needs.collect { case AvatarUrlNeeded(id) => id }

      val usersF = getUsers(userIds.toSeq)
      val userImageUrlsF = getUserImageUrls(avatarUrlUserIds.toSeq)

      for {
        users <- usersF
        userImageUrls <- userImageUrlsF
      } yield {
        val input = DataNeededResult(users = users, imageUrls = userImageUrls)
        ProcessedEmailResult(
          subject = evalTemplate(emailToSend.subject, input, emailToSend),
          htmlBody = LargeString(evalTemplate(html.body, input, emailToSend)),
          textBody = emailToSend.textTemplate.map(text => LargeString(evalTemplate(text.body, input, emailToSend)))
        )
      }
    }
  }

  private def evalTemplate(text: String, input: DataNeededResult, emailToSend: EmailToSend) = {
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
        case tags.campaign => emailToSend.campaign.getOrElse("unknown")
      }
    })
  }

  private def getTipHtml(emailToSend: EmailToSend) = {
    val predicate = (html: Option[Html]) => html.isDefined
    val transform = (tip: EmailTips) => tip match {
      case EmailTips.FriendRecommendations => peopleRecommendationsTip.render(emailToSend)
    }

    // get the first available Tip for this email that returns Some
    FutureHelpers.findMatching[EmailTips, Option[Html]](emailToSend.tips, 1, predicate, transform).map { seqOpts =>
      seqOpts.dropWhile(_.isEmpty).headOption.flatten
    }
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
    db.readOnlyReplicaAsync { implicit s => userRepo.getUsers(userIds) }
  }

  private def getUserImageUrls(userIds: Seq[Id[User]], width: Int = 100) = {
    Future.sequence(
      userIds map (userId => userCommander.getUserImageUrl(userId, width).map(url => (userId, url)))
    ) map (_.toMap)
  }

  private def getUnsubUrl(emailAddr: EmailAddress) = {
    val token = emailOptOutCommander.generateOptOutToken(emailAddr)
    s"https://www.kifi.com${com.keepit.controllers.website.routes.EmailOptOutController.optOut(token)}"
  }

  private def jsValueAsUserId(arg: JsValue) = arg.as[Id[User]]

}

