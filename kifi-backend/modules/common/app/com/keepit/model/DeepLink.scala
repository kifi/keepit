package com.keepit.model

import com.keepit.common.db._
import com.keepit.common.time._
import org.joda.time.DateTime
import com.keepit.common.service.FortyTwoServices

case class DeepLinkToken(value: String)
object DeepLinkToken {
  def apply(): DeepLinkToken = DeepLinkToken(ExternalId().id) // use ExternalIds for now. Eventually, we may move off this.
}

case class DeepLocator(value: String)
object DeepLocator {
  def ofMessageThread(message: Comment) = DeepLocator("/messages/%s".format(message.externalId))
  def ofMessageThreadList = DeepLocator("/messages")
  def ofCommentList = DeepLocator("/comments")
}

case class DeepLink(
  id: Option[Id[DeepLink]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  initatorUserId: Option[Id[User]],
  recipientUserId: Option[Id[User]],
  uriId: Option[Id[NormalizedURI]],
  urlId: Option[Id[URL]] = None, // todo(Andrew): remove Option after grandfathering process
  deepLocator: DeepLocator,
  token: DeepLinkToken = DeepLinkToken(),
  state: State[DeepLink] = DeepLinkStates.ACTIVE
) extends Model[DeepLink] {
  def withId(id: Id[DeepLink]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)

  def url(implicit fortyTwoServices: FortyTwoServices) = "%s/r/%s".format(fortyTwoServices.baseUrl, token.value)

  def withUrlId(urlId: Id[URL]) = copy(urlId = Some(urlId))

  def withNormUriId(normUriId: Id[NormalizedURI]) = copy(uriId = Some(normUriId))
}

object DeepLinkStates extends States[DeepLink]
