package com.keepit.integrity

import com.keepit.common.zookeeper.{SequenceNumberCentralConfigKey, LongCentralConfigKey}
import com.keepit.model.{RenormalizedURL, ChangedURI}

case object URIMigrationSeqNumKey extends SequenceNumberCentralConfigKey[ChangedURI] {
  val longKey = new LongCentralConfigKey {
    val name: String = "changed_uri_seq"
    val namespace = "changed_uri"
    def key: String = name
   }
}

// keep track of processed url migration
case object URLMigrationSeqNumKey extends SequenceNumberCentralConfigKey[RenormalizedURL] {
  val longKey = new LongCentralConfigKey {
    val name: String = "migrated_url_seq"
    val namespace = "changed_uri"
    def key: String = name
  }
}

// keep track of checked renormalization
case object RenormalizationCheckKey extends LongCentralConfigKey {
  val name: String = "renorm_check_lastUrlId"
  val namespace = "changed_uri"
  def key: String = name
}