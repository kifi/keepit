package com.keepit.commander

import javax.inject.Inject

import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.model.helprank.UserBookmarkClicksRepo

class UserKeepInfoCommander @Inject() (
    airbrake: AirbrakeNotifier,
    db: Database,
    userKeepInfoRepo: UserBookmarkClicksRepo) extends Logging {

}
