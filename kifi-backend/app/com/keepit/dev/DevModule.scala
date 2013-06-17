package com.keepit.dev

import net.codingwell.scalaguice.ScalaModule
import com.keepit.common.logging.Logging

class DevModule extends ScalaModule with Logging {
  def configure() {
    install(new DevCommonModule)
    install(new ShoeboxDevModule)
    install(new SearchDevModule)
  }
}
