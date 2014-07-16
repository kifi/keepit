package com.keepit.graph.model

import com.keepit.common.db.{ Id }
import com.keepit.model.{ NormalizedURI, User }
import play.api.libs.functional.syntax._
import play.api.libs.json._
import com.keepit.common.cache.TransactionalCaching.Implicits.directCacheAccess

abstract class ConnectedScore

case class ConnectedUserScore(userId: Id[User], score: Double) extends ConnectedScore

object ConnectedUserScore {
  implicit val format = (
    (__ \ 'userId).format(Id.format[User]) and
    (__ \ 'score).format[Double]
  )(ConnectedUserScore.apply, unlift(ConnectedUserScore.unapply))
}

case class ConnectedUriScore(uriId: Id[NormalizedURI], score: Double) extends ConnectedScore

object ConnectedUriScore {
  implicit val format = (
    (__ \ 'uriId).format(Id.format[NormalizedURI]) and
    (__ \ 'score).format[Double]
  )(ConnectedUriScore.apply, unlift(ConnectedUriScore.unapply))
}
