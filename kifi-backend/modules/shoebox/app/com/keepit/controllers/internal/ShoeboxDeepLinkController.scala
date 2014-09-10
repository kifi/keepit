package com.keepit.controllers.internal

import com.google.inject.Inject
import com.keepit.common.controller.{ ShoeboxServiceController, WebsiteController, ActionAuthenticator }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.inject.FortyTwoConfig
import com.keepit.model.{ DeepLink, DeepLinkRepo, DeepLocator, NormalizedURI, User }

import java.util.NoSuchElementException

import play.api.mvc.Action
import play.api.libs.json.{ Json, JsObject }

class ShoeboxDeepLinkController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  db: Database,
  deepLinkRepo: DeepLinkRepo,
  fortytwoConfig: FortyTwoConfig,
  airbrake: AirbrakeNotifier)
    extends WebsiteController(actionAuthenticator) with ShoeboxServiceController {

  def createDeepLink() = Action(parse.tolerantJson) { request =>
    val req = request.body.asInstanceOf[JsObject]
    val initiator = (req \ "initiator").asOpt[Long].map(Id[User])
    val recipient = Id[User]((req \ "recipient").as[Long])
    val uriId = Id[NormalizedURI]((req \ "uriId").as[Long])
    val locator = (req \ "locator").as[String]

    db.readWrite { implicit session =>
      deepLinkRepo.save(
        DeepLink(
          initiatorUserId = initiator,
          recipientUserId = Some(recipient),
          uriId = Some(uriId),
          urlId = None,
          deepLocator = DeepLocator(locator)
        )
      )
    }
    Ok("")
  }

  def getDeepUrl() = Action(parse.tolerantJson) { request =>
    val req = request.body.asInstanceOf[JsObject]
    val locator = (req \ "locator").as[String]
    val recipient = Id[User]((req \ "recipient").as[Long])
    val link = db.readOnlyMaster { implicit session =>
      try {
        deepLinkRepo.getByLocatorAndUser(DeepLocator(locator), recipient).token.value
      } catch {
        case e: NoSuchElementException =>
          airbrake.notify(s"Error retrieving deep url for locator: $locator and recipient $recipient", e)
          ""
      }
    }
    val url = fortytwoConfig.applicationBaseUrl + com.keepit.controllers.email.routes.EmailDeepLinkController.handle(link).toString
    Ok(Json.toJson(url))
  }

}
