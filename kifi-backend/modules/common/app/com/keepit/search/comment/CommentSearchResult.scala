package com.keepit.search.comment

import com.keepit.common.db.ExternalId
import com.keepit.model.Comment

case class CommentSearchResult(hits: Array[CommentHit], context: String)
class CommentHit(var id: Long, var timestamp: Long, var externalId: ExternalId[Comment] = null)