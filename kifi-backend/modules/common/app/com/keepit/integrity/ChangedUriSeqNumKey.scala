package com.keepit.integrity

import com.keepit.common.zookeeper.LongCentralConfigKey

case class ChangedUriSeqNumKey(val name: String = "changed_uri_seq") extends LongCentralConfigKey {
  val namespace = "changed_uri"
  def key: String = name
}