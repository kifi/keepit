package com.keepit

import play.api.Application
import com.google.inject.Injector
import com.google.inject.Key
import com.google.inject.Provider
import com.google.inject.TypeLiteral
import com.google.inject.util.Types

package object inject {
  class RichInjector(val injector: Injector) {
    def inject[A](implicit m: Manifest[A]): A = {
      m.typeArguments match {
        case Nil => injector.getInstance(m.erasure.asInstanceOf[Class[A]])
        case _   => injector.getInstance(key(m))
      }
    }
    
    private def key[A](implicit a: Manifest[A]): Key[A] = {
      val targs = a.typeArguments.map(_.erasure)
      Key.get(Types.newParameterizedType(a.erasure, targs:_*)).asInstanceOf[Key[A]]
    }
  }
  
  implicit def richInjector(injector: Injector): RichInjector = new RichInjector(injector)

  def inject[A](implicit m: Manifest[A], app: Application): A = app.global.asInstanceOf[FortyTwoGlobal].injector.inject[A]
  
  def provide[T](func: => T): Provider[T] = new Provider[T] { def get = func }
}
