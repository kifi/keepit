package com.keepit.test

import com.google.inject.Key
import com.google.inject.OutOfScopeException
import com.google.inject.Provider
import com.google.inject.Scope
import com.keepit.common.logging.Logging
import com.keepit.common.db.ExternalId
import play.api.Application
import play.api.Plugin
import play.utils.Threads

class FakeAppScope extends Scope with Logging {

  private val identifier = ExternalId[FakeAppScope]()

  def onStart(app: Application): Unit = println(s"[$identifier] scope starting...")

  def onStop(app: Application): Unit = println(s"[$identifier] scope stopping...")

  def scope[T](key: Key[T], unscoped: Provider[T]): Provider[T] = 
    throw new Exception("I'm a fake app scope, don't assign things to me please!")
}
