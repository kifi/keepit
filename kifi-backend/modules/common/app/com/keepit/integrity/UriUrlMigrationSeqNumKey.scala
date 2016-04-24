package com.keepit.integrity

import com.keepit.common.zookeeper.{ LongCentralConfigKey, SequenceNumberCentralConfigKey }
import com.keepit.model.{ ChangedURI, Keep }

case object URIMigrationSeqNumKey extends SequenceNumberCentralConfigKey[ChangedURI] {
  val longKey = new LongCentralConfigKey {
    val name: String = "changed_uri_seq"
    val namespace = "changed_uri"
    def key: String = name
  }
}

// keep track of keep deduplication
case object FixDuplicateKeepsSeqNumKey extends SequenceNumberCentralConfigKey[Keep] {
  val longKey = new LongCentralConfigKey {
    val name: String = "fix_duplicate_keeps_seq"
    val namespace = "fix_duplicate_keeps"
    def key: String = name
  }
}
