package com.keepit.common.social


import play.api.Play.current
import com.keepit.common.db._
import com.keepit.common.db.slick.DBSession._
import com.keepit.model._
import com.keepit.inject._
import com.google.inject.Inject
import com.keepit.serializer.UserSerializer
import com.keepit.serializer.BasicUserSerializer
import com.keepit.common.cache.FortyTwoCache
import com.keepit.common.cache.FortyTwoCachePlugin
import com.keepit.common.cache.Key
import play.api.libs.json.Json
import play.api.libs.json.JsObject
import scala.concurrent.duration._

case class BasicUser(externalId: ExternalId[User], firstName: String, lastName: String, facebookId: String, avatar: String) // todo: avatar is a URL

case class BasicUserUserIdKey(userId: Id[User]) extends Key[BasicUser] {
  val namespace = "basic_user_userid"
  def toKey(): String = userId.id.toString
}

class BasicUserUserIdCache @Inject() (val repo: FortyTwoCachePlugin) extends FortyTwoCache[BasicUserUserIdKey, BasicUser] {
  val ttl = 7 days
  def deserialize(obj: Any): BasicUser = BasicUserSerializer.basicUserSerializer.reads(Json.parse(obj.asInstanceOf[String]).asInstanceOf[JsObject]).get
  def serialize(basicUser: BasicUser) = BasicUserSerializer.basicUserSerializer.writes(basicUser)
  // TODO(andrew): Invalidate cache. More tricky on this multi-object cache. Right now, the data doesn't change. When we go multi-network, it will.
}

class BasicUserRepo @Inject() (socialUserRepo: SocialUserInfoRepo, userRepo: UserRepo, userCache: BasicUserUserIdCache){
  def load(userId: Id[User])(implicit session: RSession): BasicUser = userCache.getOrElse(BasicUserUserIdKey(userId)) {
    val user = userRepo.get(userId)
    val socialUserInfo = socialUserRepo.getByUser(user.id.get)
    BasicUser(
      externalId = user.externalId,
      firstName = user.firstName,
      lastName = user.lastName,
      facebookId = socialUserInfo.headOption.map(_.socialId.id).getOrElse(""), // This needs to be refactored when we switch to multiple social networks. However, the extension relies on it now.
      avatar = s"https://graph.facebook.com/${socialUserInfo.headOption.map(_.socialId.id).getOrElse("")}/picture?width=200&height=200" // todo: The extension should fetch avatars based on the size it needs
    )
  }
}
