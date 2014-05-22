package com.keepit.eliza.model

import com.keepit.common.db.Id

trait ParticipantThread {
  val threadId: Id[MessageThread]
}
