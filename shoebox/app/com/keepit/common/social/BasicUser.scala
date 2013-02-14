package com.keepit.common.social


import play.api.Play.current
import com.keepit.common.db._
import com.keepit.common.db.slick.DBSession._
import com.keepit.model._
import com.keepit.inject._

case class BasicUser(externalId: ExternalId[User], firstName: String, lastName: String, avatar: String) // todo: avatar is a URL

class BasicUserRepo {
  def load(user: User)(implicit session: RSession): BasicUser = {
  	val socialUserInfo = inject[SocialUserInfoRepo].getByUser(user.id.get)
    BasicUser(
      externalId = user.externalId,
      firstName = user.firstName,
      lastName = user.lastName,
      avatar = "https://graph.facebook.com/" + socialUserInfo.head.socialId.id + "/picture?type=square" // todo: fix?
    )
  }
}
