package com.keepit.eliza.model

import com.keepit.common.db.Id
import com.keepit.model.Keep

trait ParticipantThread {
  def keepId: Id[Keep]
}
