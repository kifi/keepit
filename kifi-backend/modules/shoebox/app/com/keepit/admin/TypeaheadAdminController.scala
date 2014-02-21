package com.keepit.controllers.admin

import com.google.inject.Inject
import com.keepit.common.db.slick.Database
import com.keepit.typeahead.socialusers.SocialUserTypeahead
import com.keepit.common.controller.{AdminController, ActionAuthenticator}
import com.keepit.model.{SocialUserBasicInfo, User}
import com.keepit.common.db.Id
import com.keepit.typeahead.TypeaheadHit
import views.html

class TypeaheadAdminController @Inject() (
  db:Database,
  actionAuthenticator:ActionAuthenticator,
  socialUserTypeahead:SocialUserTypeahead) extends AdminController(actionAuthenticator) {

  def index = AdminHtmlAction.authenticated { request =>
    Ok(html.admin.typeahead(request.user))
  }

  def socialSearch(userId:Id[User], search:String) = AdminHtmlAction.authenticated { request =>
    implicit val ord = TypeaheadHit.defaultOrdering[SocialUserBasicInfo]
    val res = socialUserTypeahead.search(userId, search) getOrElse Seq.empty[SocialUserBasicInfo]
    Ok(res.mkString("<br/>"))
  }
}

