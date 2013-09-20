package com.keepit.integrity

import com.keepit.common.zookeeper.LongCentralConfigKey

case class ChangedUriSeqNumKey(val name: String = "changed_uri_seq") extends LongCentralConfigKey {
  val namespace = "changed_uri"
  def key: String = name
}

// keep track of processed url migration
case class URLMigrationSeqNumKey(val name: String = "migrated_url_seq") extends LongCentralConfigKey {
  val namespace = "changed_uri"
  def key: String = name
}

// keep track of checked renormalization
case class RenormalizationCheckKey (val name: String = "renorm_check_lastUrlId") extends LongCentralConfigKey {
  val namespace = "changed_uri"
  def key: String = name
}