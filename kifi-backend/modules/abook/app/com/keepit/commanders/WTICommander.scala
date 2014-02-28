package com.keepit.commanders

import com.keepit.abook.model.RichSocialConnectionRepo
import com.keepit.model.SocialUserInfo
import com.keepit.common.db.slick.Database
import com.keepit.common.db.Id

import com.google.inject.{Inject, Singleton}

@Singleton
class WTICommander @Inject() (richSocialConnectionRepo: RichSocialConnectionRepo, db: Database) {

  def ripestFruit: Seq[Id[SocialUserInfo]] = db.readOnly { implicit session => richSocialConnectionRepo.getRipestFruit() }

}
