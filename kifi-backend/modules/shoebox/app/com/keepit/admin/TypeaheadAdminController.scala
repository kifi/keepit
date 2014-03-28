package com.keepit.controllers.admin

import com.google.inject.Inject
import com.keepit.common.db.slick.Database
import com.keepit.typeahead.socialusers.{KifiUserTypeahead, SocialUserTypeahead}
import com.keepit.common.controller.{AdminController, ActionAuthenticator}
import com.keepit.model._
import com.keepit.common.db.Id
import com.keepit.typeahead.TypeaheadHit
import views.html
import com.keepit.typeahead.abook.EContactTypeahead
import com.keepit.abook.ABookServiceClient
import scala.concurrent.{Promise, Future}
import com.keepit.common.akka.SafeFuture
import com.keepit.common.concurrent.ExecutionContext
import play.api.libs.json.{Json, JsArray}
import com.keepit.commanders.TypeaheadCommander

class TypeaheadAdminController @Inject() (
  db:Database,
  actionAuthenticator:ActionAuthenticator,
  abookServiceClient:ABookServiceClient,
  typeaheadCommander:TypeaheadCommander,
  kifiUserTypeahead:KifiUserTypeahead,
  econtactTypeahead:EContactTypeahead,
  socialUserTypeahead:SocialUserTypeahead) extends AdminController(actionAuthenticator) {

  implicit val fj = ExecutionContext.fj

  def index = AdminHtmlAction.authenticated { request =>
    Ok(html.admin.typeahead(request.user))
  }

  def refreshPrefixFilter(userId:Id[User]) = AdminHtmlAction.authenticatedAsync { request =>
    val abookF  = econtactTypeahead.refresh(userId)
    val socialF = socialUserTypeahead.refresh(userId)
    val kifiF   = kifiUserTypeahead.refresh(userId)
    for {
      socialRes <- socialF
      kifiRes   <- kifiF
      abookRes  <- abookF
    } yield {
      Ok(s"PrefixFilters for $userId has been refreshed")
    }
  }

  def refreshPrefixFiltersByIds() = AdminHtmlAction.authenticatedAsync(parse.json) { request =>
    val jsArray = request.body.asOpt[JsArray] getOrElse JsArray()
    val userIds = jsArray.value map { x => Id[User](x.as[Long]) }
    val abookF = econtactTypeahead.refreshByIds(userIds) // remote
    for {
      socialRes <- socialUserTypeahead.refreshByIds(userIds)
      kifiRes   <- kifiUserTypeahead.refreshByIds(userIds)
      abookRes  <- abookF
    } yield {
      Ok(s"PrefixFilters for #${userIds.length} updated; ${userIds.take(50).mkString(",")}")
    }
  }

  def refreshAllPrefixFilters() = AdminHtmlAction.authenticatedAsync { request =>
    val abookF  = econtactTypeahead.refreshAll() // remote
    for {
      socialRes <- socialUserTypeahead.refreshAll()
      kifiRes   <- kifiUserTypeahead.refreshAll()
      abookRes  <- abookF
    } yield {
      Ok(s"All PrefixFilters updated")
    }
  }

  def refreshAll(filterType:String) = AdminHtmlAction.authenticatedAsync { request =>
    val resF = filterType.trim match {
      case "contact" => econtactTypeahead.refreshAll()
      case "social"  => socialUserTypeahead.refreshAll() // may breakdown further into FB, LNKD ...
      case "kifi"    => kifiUserTypeahead.refreshAll()
      case _ => Promise[Unit].future
    }
    resF map { res =>
      Ok(s"All PrefixFilters updated for $filterType")
    }
  }

  def userSearch(userId:Id[User], query:String) = AdminHtmlAction.authenticated { request =>
    implicit val ord = TypeaheadHit.defaultOrdering[User]
    val res = kifiUserTypeahead.search(userId, query) getOrElse Seq.empty[User]
    Ok(res.map{ info => s"KifiUser: id=${info.id} name=${info.fullName} <br/>" }.mkString(""))
  }

  def socialSearch(userId:Id[User], query:String) = AdminHtmlAction.authenticated { request =>
    implicit val ord = TypeaheadHit.defaultOrdering[SocialUserBasicInfo]
    val res = socialUserTypeahead.search(userId, query) getOrElse Seq.empty[SocialUserBasicInfo]
    Ok(res.map{ info => s"SocialUser: id=${info.id} name=${info.fullName} network=${info.networkType} <br/>" }.mkString(""))
  }
  
  def contactSearch(userId:Id[User], query:String) = AdminHtmlAction.authenticatedAsync { request =>
    implicit val ord = TypeaheadHit.defaultOrdering[EContact]
    val localF = econtactTypeahead.asyncSearch(userId, query) map { resOpt =>
      val res = resOpt getOrElse Seq.empty[EContact]
      log.info(s"[contactSearch($userId,$query)-LOCAL] res=(${res.length});${res.take(10).mkString(",")}")
      res
    }
    val abookF = abookServiceClient.prefixSearch(userId, query)
    Future.firstCompletedOf(Seq(localF, abookF)) map { res =>
      Ok(res.map{ e => s"EContact: id=${e.id} email=${e.email} name=${e.name} <br/>" }.mkString(""))
    }
  }

  def search(userId:Id[User], query:String, limit:Int, pictureUrl:Boolean, filterJoinedUsers:Boolean) = AdminHtmlAction.authenticatedAsync { request =>
    typeaheadCommander.searchWithInviteStatus(userId, query, Some(limit), pictureUrl, filterJoinedUsers) map { res => // hack
    // Ok(res.map(c => s"label=${c.label} score=${c.score} status=${c.status} value=${c.value}<br/>").mkString(""))
    Ok(
        "<table border=1><tr><td>label</td><td>networkType</td><td>score</td><td>status</td><td>value</td><td>image</td></tr>" +
        res.map(c => s"<tr><td>${c.label}</td><td>${c.networkType}</td><td>${c.score}</td><td>${c.status}</td><td>${c.value}</td><td>${c.image.getOrElse("")}</td></tr>").mkString("") +
        "</table>"
      )
    }
  }

}

