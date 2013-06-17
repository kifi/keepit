package com.keepit

import net.codingwell.scalaguice.InjectorExtensions._

import com.google.inject.Provider

import play.api.Application

package object inject {
  def inject[A](implicit m: Manifest[A], app: Application): A = app.global.asInstanceOf[FortyTwoGlobal].injector.instance[A]
  def provide[T](func: => T): Provider[T] = new Provider[T] { def get = func }
}
