package com.keepit.commanders.emails

import com.google.inject.{ ImplementedBy, Inject }
import com.keepit.commanders.UserCommander
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.mail.EmailAddress
import com.keepit.inject.FortyTwoConfig
import com.keepit.common.mail.template.{ EmailToSend, TagWrapper, tags, EmailTips }
import com.keepit.common.mail.template.helpers.toHttpsUrl
import com.keepit.common.mail.template.Tag._
import com.keepit.model.{ UserEmailAddressRepo, UserRepo, User }
import com.keepit.social.BasicUser
import play.api.libs.json.{ Json, JsValue }
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.twirl.api.Html

import scala.concurrent.Future

@ImplementedBy(classOf[EmailTemplateProcessorImpl])
trait EmailTemplateProcessor {
  def process(module: EmailToSend): Future[Html]
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
    val templatesF = tipHtmlF.map(_.map(tipHtml => Seq(emailToSend.htmlTemplate, tipHtml))).
      getOrElse(Future.successful(Seq(emailToSend.htmlTemplate)))

    templatesF.flatMap[Html] { templates =>
      val html = views.html.email.black.layout(templates)
      val needs = getNeededObjects(html, emailToSend)

      val userIds = needs.collect { case UserNeeded(id) => id }
      val avatarUrlUserIds = needs.collect { case AvatarUrlNeeded(id) => id }

      val usersF = getUsers(userIds.toSeq)
      val userImageUrlsF = getUserImageUrls(avatarUrlUserIds.toSeq)

      for {
        users <- usersF
        userImageUrls <- userImageUrlsF
      } yield {
        val input = DataNeededResult(users = users, imageUrls = userImageUrls)
        evalHtml(html, input, emailToSend)
      }
    }
  }

  private def evalHtml(html: Html, input: DataNeededResult, emailToSend: EmailToSend) = {
    Html(tagRegex.replaceAllIn(html.body, { rMatch =>
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
    }))
  }

  private def getTipHtml(emailToSend: EmailToSend) = {
    // get the first available Tip for this email that returns Some
    val tipStream = emailToSend.tips.toStream.collect {
      case EmailTips.FriendRecommendations => peopleRecommendationsTip.render(emailToSend)
      case _ => None
    }
    tipStream.find(_.isDefined).flatten
  }

  // used to gather the types of objects we need to replace the tags with real values
  private def getNeededObjects(html: Html, emailToSend: EmailToSend) = {
    tagRegex.findAllMatchIn(html.body).map[NeededObject] { rMatch =>
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

