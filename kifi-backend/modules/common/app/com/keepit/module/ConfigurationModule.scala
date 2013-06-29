package com.keepit.module

import net.codingwell.scalaguice.ScalaModule

abstract class ConfigurationModule(modules: ScalaModule*) extends ScalaModule {
  final def configure() {
    println(s"Configuring ${this}")
    modules.foreach { module =>
      println(s"Install ${module}")
      install(module)
    }
  }
}
