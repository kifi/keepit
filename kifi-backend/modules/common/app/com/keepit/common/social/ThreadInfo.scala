package com.keepit.common.social

import org.joda.time.DateTime
import com.keepit.common.db._
import com.keepit.model._

case class ThreadInfo(
    externalId: ExternalId[Comment],
    recipients: Seq[BasicUser],
    digest: String,
    lastAuthor: ExternalId[User],
    messageCount: Long,
    messageTimes: Map[ExternalId[Comment], DateTime],
    createdAt: DateTime,
    lastCommentedAt: DateTime)
