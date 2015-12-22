package com.keepit.eliza.model

import com.keepit.common.db.Id
import com.keepit.model.Keep

trait ParticipantThread {
  val threadId: Id[MessageThread]
  def keepId: Id[Keep]
}
