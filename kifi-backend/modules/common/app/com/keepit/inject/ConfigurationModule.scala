package com.keepit.inject

import _root_.net.codingwell.scalaguice.ScalaModule
import com.keepit.common.logging.Logging

abstract class AbstractModuleAccessor extends ScalaModule {
  protected def install0(module: ScalaModule) = install(module)
}

abstract class ConfigurationModule extends ScalaModule with Logging {

  final def configure() {
    log.info(s"Configuring ${this}")

    for (field <- this.getClass.getDeclaredFields) yield {
      log.info("\nGot " + field.getName)
      field.setAccessible(true)
      field.get(this) match {
        case module: ScalaModule =>  
          log.info(s"Install ${module}")
          install(module)
        case _ => 
      }
    }
  }
}
