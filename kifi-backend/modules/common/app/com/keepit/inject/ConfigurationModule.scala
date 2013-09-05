package com.keepit.inject

import _root_.net.codingwell.scalaguice.ScalaModule
import com.keepit.common.logging.Logging

abstract class ConfigurationModule extends ScalaModule with Logging { self =>
  final def configure() {
    log.info(s"Configuring ${this}")
    for (field <- self.getClass.getDeclaredFields) yield {
      field.setAccessible(true)
      field.get(self) match {
        case module: ScalaModule =>  
          log.info(s"Install ${module}")
          install(module)
        case _ => 
      }
    }
  }
}
