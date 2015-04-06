package com.keepit.controllers.mobile

import com.google.inject.Inject
import com.keepit.common.controller.{ UserActionsHelper, ShoeboxServiceController, UserActions }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.SystemAdminMailSender
import com.keepit.common.mail.{ SystemEmailAddress, ElectronicMail, ElectronicMailCategory }
import com.keepit.common.mail.template.EmailToSend
import com.keepit.common.time.Clock
import com.keepit.model.{ NotificationCategory, Restriction, NormalizedURIRepo }
import com.keepit.normalizer.NormalizedURIInterner
import play.api.libs.json.Json

class MobileUriController @Inject() (
    db: Database,
    normalizedUriRepo: NormalizedURIRepo,
    normalizedUriInterner: NormalizedURIInterner,
    systemAdminMailSender: SystemAdminMailSender,
    clock: Clock,
    val userActionsHelper: UserActionsHelper) extends UserActions with ShoeboxServiceController {

  def flagContent() = UserAction(parse.tolerantJson) { request =>
    val jsonBody = request.body
    val reason = (jsonBody \ "reason").as[String]
    val url = (jsonBody \ "url").as[String]

    db.readWrite { implicit s =>
      normalizedUriInterner.getByUri(url).map { uri =>
        log.info(s"[MobileUriController] ${uri} marked as ${reason} content!")
        systemAdminMailSender.sendMail(ElectronicMail(
          from = SystemEmailAddress.ENG,
          to = List(SystemEmailAddress.EISHAY, SystemEmailAddress.ANDREW, SystemEmailAddress.STEPHEN),
          subject = s"url [${uri.id.get}]: ${uri.url} flagged as ${reason} content!!!",
          htmlBody = s"""uri: ${uri.url} (${uri.id.get})<br>flagged by user ${request.user.fullName} (${request.user.username} ${request.user.id}).<br>Check it out: <a href="https://admin.kifi.com/admin/scraped/${uri.id.get}"></a>""",
          category = NotificationCategory.toElectronicMailCategory(NotificationCategory.System.ADMIN)))
        NoContent
      }
    }.getOrElse(BadRequest(Json.obj("error" -> "uri_not_found")))
  }
}
