package com.keepit.eliza.model

import com.keepit.common.mail.template.EmailTrackingParam
import com.keepit.model.User
import com.keepit.eliza.util.MessageSegment
import com.keepit.common.db.Id
import com.keepit.rover.model.RoverUriSummary

case class ThreadEmailInfo(
    pageUrl: String,
    pageName: String,
    pageTitle: String,
    isInitialEmail: Boolean,
    heroImageUrl: Option[String],
    pageDescription: Option[String],
    participants: Seq[String],
    conversationStarter: String,
    invitedByUser: Option[User],
    unsubUrl: Option[String],
    muteUrl: Option[String],
    readTimeMinutes: Option[Int],
    isDeepLink: Boolean = false) {

  def pageUrlWithTracking(subAction: String) =
    if (isDeepLink) pageUrl + "?" + EmailTrackingParam.paramName + "=" + EmailTrackingParam(subAction = Some(subAction)).encode
    else pageUrl
}

case class ExtendedThreadItem(senderShortName: String, senderFullName: String, imageUrl: Option[String], segments: Seq[MessageSegment])

case class ThreadEmailData(
  thread: MessageThread,
  allUserIds: Set[Id[User]],
  allUsers: Map[Id[User], User],
  allUserImageUrls: Map[Id[User], String],
  uriSummary: Option[RoverUriSummary])
