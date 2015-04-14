package com.keepit.common.zookeeper

import com.keepit.common.db.Id
import com.keepit.model.User

class ShardingCommander {
  def inShard(user: Id[User]): Boolean = {
    val hash = user.id
    
  }
}
