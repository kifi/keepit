package com.keepit.controllers.admin

import com.google.inject.Inject
import com.keepit.common.db.slick.Database
import com.keepit.typeahead.socialusers.SocialUserTypeahead
import com.keepit.common.controller.{AdminController, ActionAuthenticator}
import com.keepit.model.{EContact, SocialUserBasicInfo, User}
import com.keepit.common.db.Id
import com.keepit.typeahead.TypeaheadHit
import views.html
import com.keepit.typeahead.abook.EContactTypeahead
import com.keepit.abook.ABookServiceClient
import scala.concurrent.Future
import com.keepit.common.akka.SafeFuture
import play.api.libs.concurrent.Execution.Implicits.defaultContext

class TypeaheadAdminController @Inject() (
  db:Database,
  actionAuthenticator:ActionAuthenticator,
  abookServiceClient:ABookServiceClient,
  econtactTypeahead:EContactTypeahead,
  socialUserTypeahead:SocialUserTypeahead) extends AdminController(actionAuthenticator) {

  def index = AdminHtmlAction.authenticated { request =>
    Ok(html.admin.typeahead(request.user))
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

  def search(userId:Id[User], query:String) = AdminHtmlAction.authenticatedAsync { request =>
    val socialF = SafeFuture {
      socialUserTypeahead.search(userId, query)(TypeaheadHit.defaultOrdering[SocialUserBasicInfo]) getOrElse Seq.empty[SocialUserBasicInfo]
    }
    val contactF = econtactTypeahead.asyncSearch(userId, query)(TypeaheadHit.defaultOrdering[EContact]) map { resOpt =>
      resOpt getOrElse Seq.empty[EContact]
    }
    for {
      socialRes <- socialF
      contactRes <- contactF
    } yield {
      Ok(socialRes.map{ info => s"SocialUser: id=${info.id} name=${info.fullName} network=${info.networkType} <br/>" }.mkString("") +
         contactRes.map{ e => s"EContact: id=${e.id} email=${e.email} name=${e.name} <br/>" }.mkString(""))
    }
  }

}

