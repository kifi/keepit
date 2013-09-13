package com.keepit.controllers.ext

import com.keepit.classify.{Domain, DomainRepo, DomainStates}
import com.keepit.common.controller.{ShoeboxServiceController, BrowserExtensionController, ActionAuthenticator}
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._
import com.keepit.model._
import com.keepit.common.time._

import play.api.Play.current
import play.api.libs.json.{JsObject, Json}

import com.google.inject.Inject
import com.keepit.common.net.URI
import com.keepit.controllers.core.NetworkInfoLoader
import com.keepit.common.social.BasicUserRepo
import com.keepit.social.BasicUser
import com.keepit.common.analytics.{EventPersister, Event, EventFamilies, Events}
import play.api.libs.concurrent.Akka
import com.keepit.common.service.FortyTwoServices

class ExtUserController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  db: Database,
  domainRepo: DomainRepo,
  userToDomainRepo: UserToDomainRepo,
  networkInfoLoader: NetworkInfoLoader,
  userRepo: UserRepo,
  userConnectionRepo: UserConnectionRepo,
  experimentRepo: UserExperimentRepo,
  basicUserRepo: BasicUserRepo,
  EventPersister: EventPersister,
  implicit val clock: Clock,
  implicit val fortyTwoServices: FortyTwoServices)
    extends BrowserExtensionController(actionAuthenticator) with ShoeboxServiceController {


  def logEvent() = AuthenticatedJsonToJsonAction { request =>
    Akka.future {
      val o = request.body
      val eventTime = clock.now.minusMillis((o \ "msAgo").asOpt[Int].getOrElse(0))
      val eventFamily = EventFamilies((o \ "eventFamily").as[String])
      val eventName = (o \ "eventName").as[String]
      val installId = (o \ "installId").as[String]
      val metaData = (o \ "metaData").asOpt[JsObject].getOrElse(Json.obj())
      val prevEvents = (o \ "prevEvents").asOpt[Seq[String]].getOrElse(Seq.empty).map(ExternalId[Event])
      val user = db.readOnly { implicit s => userRepo.get(request.user.id.get) }
      val event = Events.userEvent(eventFamily, eventName, user, request.experiments, installId, metaData, prevEvents, eventTime)
      log.debug("Created new event: %s".format(event))
      EventPersister.persist(event)
    }
    Ok
  }

  def getLoggedIn() = AuthenticatedJsonAction { request =>
    Ok("0")
  }

  def getNetworks(friendExtId: ExternalId[User]) = AuthenticatedJsonAction { request =>
    Ok(Json.toJson(networkInfoLoader.load(request.user.id.get, friendExtId)))
  }

  def getFriends() = AuthenticatedJsonAction { request =>
    val basicUsers = db.readOnly { implicit s =>
      if (canMessageAllUsers(request.user.id.get)) {
        userRepo.allExcluding(UserStates.PENDING, UserStates.BLOCKED, UserStates.INACTIVE)
          .collect { case u if u.id.get != request.user.id.get => BasicUser.fromUser(u) }.toSet
      } else {
        userConnectionRepo.getConnectedUsers(request.user.id.get).map(basicUserRepo.load)
      }
    }

    // Apologies for this code. "Personal favor" for Danny. Doing it right should be speced and requires
    // two models, service clients, and caches.
    val iNeededToDoThisIn20Minutes = if (request.experiments.contains(ExperimentTypes.ADMIN)) {
      Seq(
        BasicUser(ExternalId[User]("42424242-4242-4242-4242-424242424201"), "FortyTwo Engineering", "", "0.jpg"),
        BasicUser(ExternalId[User]("42424242-4242-4242-4242-424242424202"), "FortyTwo Family", "", "0.jpg"),
        BasicUser(ExternalId[User]("42424242-4242-4242-4242-424242424203"), "FortyTwo Product", "", "0.jpg")
      )
    } else {
      Seq()
    }
    Ok(Json.toJson(basicUsers ++ iNeededToDoThisIn20Minutes))
  }


  private def canMessageAllUsers(userId: Id[User])(implicit s: RSession): Boolean = {
    experimentRepo.hasExperiment(userId, ExperimentTypes.CAN_MESSAGE_ALL_USERS)
  }

  def suppressSliderForSite() = AuthenticatedJsonToJsonAction { request =>
    val json = request.body
    val host: String = URI.parse((json \ "url").as[String]).get.host.get.name
    val suppress: Boolean = (json \ "suppress").as[Boolean]
    db.readWrite(attempts = 3) { implicit s =>
      val domain = domainRepo.get(host, excludeState = None) match {
        case Some(d) if d.isActive => d
        case Some(d) => domainRepo.save(d.withState(DomainStates.ACTIVE))
        case None => domainRepo.save(Domain(hostname = host))
      }
      userToDomainRepo.get(request.userId, domain.id.get, UserToDomainKinds.NEVER_SHOW, excludeState = None) match {
        case Some(utd) if (utd.isActive != suppress) =>
          userToDomainRepo.save(utd.withState(if (suppress) UserToDomainStates.ACTIVE else UserToDomainStates.INACTIVE))
        case Some(utd) => utd
        case None =>
          userToDomainRepo.save(UserToDomain(None, request.userId, domain.id.get, UserToDomainKinds.NEVER_SHOW, None))
      }
    }

    Ok(Json.obj("host" -> host, "suppressed" -> suppress))
  }

}
