package com.keepit.inject

import _root_.net.codingwell.scalaguice.ScalaModule
import com.keepit.common.logging.Logging

abstract class AbstractModuleAccessor extends ScalaModule {
  protected def install0(module: ScalaModule) = install(module)
}

trait ConfigurationModule extends AbstractModuleAccessor with Logging { 

  final def configure() {
    log.info(s"Configuring ${this}")

    for (field <- this.getClass.getMethods) {
      if (field.getReturnType.getGenericInterfaces.contains(classOf[ScalaModule])) {
        val startTime = System.currentTimeMillis
        val module = field.invoke(this).asInstanceOf[ScalaModule]
        install0(module)
        log.info(s"Installing ${module.getClass.getSimpleName}... took ${System.currentTimeMillis-startTime}ms")
      }
    }
  }
}
