package com.keepit

import play.api.Application
import com.google.inject.Injector
import com.google.inject.Key
import com.google.inject.Provider
import com.google.inject.util.Types

package object inject {
  def inject[A](implicit m: Manifest[A], app: Application): A = new RichInjector(app.global.asInstanceOf[FortyTwoGlobal].injector).inject[A]
  def provide[T](func: => T): Provider[T] = new Provider[T] { def get = func }
}
