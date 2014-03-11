package com.keepit.controllers.ext

import com.google.inject.Inject

import com.keepit.common.akka.SafeFuture
import com.keepit.common.controller.{ShoeboxServiceController, BrowserExtensionController, ActionAuthenticator}
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.model.{EContact, SocialUserInfo, SocialUserBasicInfo, User, InvitationRepo}
import com.keepit.typeahead.TypeaheadHit
import com.keepit.typeahead.abook.EContactTypeahead
import com.keepit.typeahead.socialusers.SocialUserTypeahead

import org.joda.time.DateTime

import play.api.Play.current
import play.api.libs.concurrent.Akka
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.{Json, JsArray, JsNumber, JsObject, JsString, JsValue}

class ExtNonUserSearchController @Inject() (
  db: Database,
  actionAuthenticator: ActionAuthenticator,
  econtactTypeahead: EContactTypeahead,
  socialUserTypeahead: SocialUserTypeahead,
  invitationRepo: InvitationRepo)
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
      val (sInvitedAt, cInvitedAt) = db.readOnly { implicit session =>
        (invitationRepo.getLastInvitedAtBySenderIdAndRecipientSocialUserIds(request.userId, socialResN.map(_.id)),
         invitationRepo.getLastInvitedAtBySenderIdAndRecipientEContactIds(request.userId, contactResN.map(_.id.get)))
      }
      Ok(serializeResults(socialResN, contactResN, sInvitedAt, cInvitedAt))
    }
  }

  def serializeResults(
      infos: Seq[SocialUserBasicInfo],
      contacts: Seq[EContact],
      infosInvitedAt: Map[Id[SocialUserInfo], DateTime],
      contactsInvitedAt: Map[Id[EContact], DateTime]): JsArray = {
    JsArray(infos.map {i => JsObject(Seq[(String, JsValue)](
      "name" -> JsString(i.fullName),
      "id" -> JsString(s"${i.networkType}/${i.socialId.id}")) ++
      infosInvitedAt.get(i.id).map {t => "invited" -> JsNumber(t.getMillis)} ++
      i.pictureUrl.map {url => "pic" -> JsString(url)})}) ++
    JsArray(contacts.map {c => JsObject(Seq[(String, JsValue)](
      "email" -> JsString(c.email)) ++
      c.name.map {name => "name" -> JsString(name)} ++
      contactsInvitedAt.get(c.id.get).map {t => "invited" -> JsNumber(t.getMillis)})})
  }

}
