package com.keepit.controllers.admin

import com.google.inject.Inject
import com.keepit.common.db.slick.Database
import com.keepit.typeahead.socialusers.{ KifiUserTypeahead, SocialUserTypeahead }
import com.keepit.common.controller._
import com.keepit.model._
import com.keepit.common.db.Id
import com.keepit.typeahead.TypeaheadHit
import views.html
import com.keepit.abook.ABookServiceClient
import com.keepit.common.concurrent.ExecutionContext
import play.api.libs.json.Json
import com.keepit.commanders.TypeaheadCommander

class TypeaheadAdminController @Inject() (
    db: Database,
    userActionsHelper: UserActionsHelper,
    abookServiceClient: ABookServiceClient,
    typeaheadCommander: TypeaheadCommander,
    kifiUserTypeahead: KifiUserTypeahead,
    socialUserTypeahead: SocialUserTypeahead) extends UserAPIController(userActionsHelper) with ShoeboxServiceController {

  implicit val fj = ExecutionContext.fj

  def index = (UserAction andThen AdminCheck) { request =>
    Ok(html.admin.typeahead(request.user))
  }

  def refreshPrefixFilter(filterType: String) = AdminUserAction.async { request =>
    val futureRefresh = filterType.trim match {
      case "contact" => abookServiceClient.refreshPrefixFilter(request.userId)
      case "social" => socialUserTypeahead.refresh(request.userId) // may breakdown further into FB, LNKD ...
      case "kifi" => kifiUserTypeahead.refresh(request.userId)
      case _ => throw new IllegalArgumentException(s"Does not recognize filter type $filterType")
    }

    futureRefresh.map { _ =>
      Ok(s"PrefixFilter ($filterType) for ${request.userId} has been refreshed")
    }
  }

  def refreshPrefixFiltersByIds(filterType: String) = AdminUserAction.async(parse.tolerantJson) { request =>
    val userIds = (request.body).as[Seq[Id[User]]]
    val futureRefresh = filterType.trim match {
      case "contact" => abookServiceClient.refreshPrefixFiltersByIds(userIds)
      case "social" => socialUserTypeahead.refreshByIds(userIds) // may breakdown further into FB, LNKD ...
      case "kifi" => kifiUserTypeahead.refreshByIds(userIds)
      case _ => throw new IllegalArgumentException(s"Does not recognize filter type $filterType")
    }

    futureRefresh.map { _ =>
      Ok(s"PrefixFilter ($filterType) for ${userIds.length} users ${userIds.take(50).mkString(",")} updated")
    }
  }

  def refreshAll(filterType: String) = AdminUserAction.async { request =>
    val futureRefresh = filterType.trim match {
      case "contact" => abookServiceClient.refreshAllFilters()
      case "social" => socialUserTypeahead.refreshAll() // may breakdown further into FB, LNKD ...
      case "kifi" => kifiUserTypeahead.refreshAll()
      case _ => throw new IllegalArgumentException(s"Does not recognize filter type $filterType")
    }

    futureRefresh.map { _ =>
      Ok(s"All ($filterType) PrefixFilters updated for all users")
    }
  }

  def refreshAllPrefixFilters() = AdminUserAction.async { request =>
    val abookF = abookServiceClient.refreshAllFilters() // remote
    val kifiF = kifiUserTypeahead.refreshAll()
    val socialF = socialUserTypeahead.refreshAll()
    for {
      socialRes <- socialF
      kifiRes <- kifiF
      abookRes <- abookF
    } yield {
      Ok(s"All PrefixFilters updated for all users")
    }
  }

  def userSearch(userId: Id[User], query: String) = AdminUserAction.async { request =>
    implicit val ord = TypeaheadHit.defaultOrdering[User]
    kifiUserTypeahead.topN(userId, query, None) map { res =>
      Ok(res.map { hit => s"KifiUser: id=${hit.info.id} name=${hit.info.fullName} <br/>" }.mkString(""))
    }
  }

  def socialSearch(userId: Id[User], query: String) = AdminUserAction.async { request =>
    implicit val ord = TypeaheadHit.defaultOrdering[SocialUserBasicInfo]
    socialUserTypeahead.topN(userId, query, None) map { res =>
      Ok(res.map { hit => s"SocialUser: id=${hit.info.id} name=${hit.info.fullName} network=${hit.info.networkType} <br/>" }.mkString(""))
    }
  }

  def contactSearch(userId: Id[User], query: String) = AdminUserAction.async { request =>
    abookServiceClient.prefixQuery(userId, query, None) map { hits =>
      log.info(s"[contactSearch($userId,$query)-LOCAL] res=(${hits.length});${hits.take(10).mkString(",")}")
      Ok(Json.toJson(hits))
    }
  }

  def search(userId: Id[User], query: String, limit: Int, pictureUrl: Boolean, dedupEmail: Boolean) = AdminUserAction.async { request =>
    typeaheadCommander.searchWithInviteStatus(userId, query, Some(limit), pictureUrl, dedupEmail) map { res => // hack
      Ok(
        "<table border=1><tr><td>label</td><td>networkType</td><td>score</td><td>status</td><td>value</td><td>image</td><td>email</td><td>inviteLastSentAt</td></tr>" +
          res.map(c => s"<tr><td>${c.label}</td><td>${c.networkType}</td><td>${c.score}</td><td>${c.status}</td><td>${c.value}</td><td>${c.image.getOrElse("")}</td><td>${c.email}</td><td>${c.inviteLastSentAt}</td></tr>").mkString("") +
          "</table>"
      )
    }
  }

}

