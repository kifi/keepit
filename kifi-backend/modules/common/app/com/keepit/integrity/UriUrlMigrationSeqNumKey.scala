package com.keepit.integrity

import com.keepit.common.zookeeper.LongCentralConfigKey

case object URIMigrationSeqNumKey extends LongCentralConfigKey {
  val name: String = "changed_uri_seq"
  val namespace = "changed_uri"
  def key: String = name
}

// keep track of processed url migration
case object URLMigrationSeqNumKey extends LongCentralConfigKey {
  val name: String = "migrated_url_seq"
  val namespace = "changed_uri"
  def key: String = name
}

// keep track of checked renormalization
case object RenormalizationCheckKey extends LongCentralConfigKey {
  val name: String = "renorm_check_lastUrlId"
  val namespace = "changed_uri"
  def key: String = name
}