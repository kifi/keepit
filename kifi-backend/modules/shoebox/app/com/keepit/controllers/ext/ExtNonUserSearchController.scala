package com.keepit.controllers.ext

import com.keepit.common.akka.SafeFuture
import com.keepit.common.controller.{ShoeboxServiceController, BrowserExtensionController, ActionAuthenticator}
import com.keepit.model.{EContact, SocialUserBasicInfo}
import com.keepit.typeahead.TypeaheadHit
import com.keepit.typeahead.abook.EContactTypeahead
import com.keepit.typeahead.socialusers.SocialUserTypeahead

import play.api.Play.current
import play.api.libs.concurrent.Akka
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.{Json, JsArray}

import com.google.inject.Inject

class ExtNonUserSearchController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  econtactTypeahead: EContactTypeahead,
  socialUserTypeahead: SocialUserTypeahead)
    extends BrowserExtensionController(actionAuthenticator) with ShoeboxServiceController {

  def findNonUsers(q: String, n: Int) = JsonAction.authenticatedAsync { request =>
    val socialF = new SafeFuture({
      socialUserTypeahead.asyncSearch(request.userId, q)(TypeaheadHit.defaultOrdering[SocialUserBasicInfo])
    }) map { _ getOrElse Seq.empty[SocialUserBasicInfo] }
    val contactF = new SafeFuture ({
      econtactTypeahead.asyncSearch(request.userId, q)(TypeaheadHit.defaultOrdering[EContact])
    }) map { _ getOrElse Seq.empty[EContact] }
    for {
      socialRes <- socialF
      contactRes <- contactF
    } yield {
      val socialResN = socialRes.filterNot(_.userId.isDefined).take(n);
      val contactResN = contactRes.filterNot(_.contactUserId.isDefined).take(n - socialResN.size)
      Ok(Json.arr(socialResN) ++
         JsArray(contactResN.map {c => Json.obj("name" -> c.name, "email" -> c.email)}))
    }
  }

}
