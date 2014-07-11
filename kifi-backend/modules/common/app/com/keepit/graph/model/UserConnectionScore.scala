package com.keepit.graph.model

import com.keepit.common.db.{ Id }
import com.keepit.model.{ NormalizedURI, User }
import play.api.libs.functional.syntax._
import play.api.libs.json._
import com.keepit.common.cache.TransactionalCaching.Implicits.directCacheAccess

abstract class UserConnectionScore

case class UserConnectionSocialScore(userId: Id[User], score: Double) extends UserConnectionScore

object UserConnectionSocialScore {
  implicit val format = (
    (__ \ 'userId).format(Id.format[User]) and
    (__ \ 'score).format[Double]
  )(UserConnectionSocialScore.apply, unlift(UserConnectionSocialScore.unapply))
}

case class UserConnectionFeedScore(uriId: Id[NormalizedURI], score: Double) extends UserConnectionScore

object UserConnectionFeedScore {
  implicit val format = (
    (__ \ 'uriId).format(Id.format[NormalizedURI]) and
    (__ \ 'score).format[Double]
  )(UserConnectionFeedScore.apply, unlift(UserConnectionFeedScore.unapply))
}
