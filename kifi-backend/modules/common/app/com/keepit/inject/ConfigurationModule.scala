package com.keepit.inject

import _root_.net.codingwell.scalaguice.ScalaModule
import com.keepit.common.logging.Logging

abstract class ConfigurationModule(modules: ScalaModule*) extends ScalaModule with Logging {
  final def configure() {
    log.info(s"Configuring ${this}")
    modules.foreach { module =>
      log.info(s"Install ${module}")
      install(module)
    }
  }
}
