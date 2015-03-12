package com.keepit.controllers.mobile

import com.google.inject.Inject
import com.keepit.common.controller.{ UserActionsHelper, ShoeboxServiceController, UserActions }
import com.keepit.common.db.slick.Database
import com.keepit.model.{ NotifyPreference, UserNotifyPreferenceRepo }

class MobilePreferenceController @Inject() (
    val userActionsHelper: UserActionsHelper,
    db: Database,
    notifyPreferenceRepo: UserNotifyPreferenceRepo) extends UserActions with ShoeboxServiceController {

  def setNotifyPreferences = UserAction(parse.tolerantJson) { request =>
    val recosReminderOpt = (request.body \ "recos_reminder").asOpt[Boolean]
    recosReminderOpt.map { recosReminderSend =>
      db.readWrite { implicit s =>
        notifyPreferenceRepo.setNotifyPreference(request.user.id.get, NotifyPreference.RECOS_REMINDER, recosReminderSend)
      }
    }
    NoContent
  }

}
