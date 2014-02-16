package com.keepit.common.config

import play.api.{Configuration, Play}

object GlobalConfig {
  /**
   * Wrapper around Play.current.configuration that returns None if there is no running application
   */
  def safeConfig = try {Play.current.configuration} catch { case t:Throwable => Configuration.empty}
}