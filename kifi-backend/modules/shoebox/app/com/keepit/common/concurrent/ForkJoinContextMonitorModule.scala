package com.keepit.common.concurrent

import com.keepit.inject.AppScoped
import net.codingwell.scalaguice.ScalaModule
import com.google.inject.{Provides, Singleton}

trait ForkJoinContextMonitorModule extends ScalaModule

case class ProdForkJoinContextMonitorModule() extends ForkJoinContextMonitorModule {

  def configure {
    bind[ForkJoinExecContextPlugin].to[ForkJoinExecContextPluginImpl].in[AppScoped]
    install(ProdForkJoinContextMonitorModule())
  }
}

case class DevForkJoinContextMonitorModule() extends ForkJoinContextMonitorModule {

  def configure {
    bind[ForkJoinExecContextPlugin].to[ForkJoinExecContextPluginImpl].in[AppScoped]
    install(DevForkJoinContextMonitorModule())
  }

}

