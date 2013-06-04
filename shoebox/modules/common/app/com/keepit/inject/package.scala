package com.keepit.inject

import play.api.Application
import com.google.inject.Injector
import com.google.inject.Key
import com.google.inject.Provider
import com.google.inject.util.Types

class RichInjector(val injector: Injector) extends AnyVal {
  def inject[A](implicit m: Manifest[A]): A = {
    m.typeArguments match {
      case Nil => injector.getInstance(m.runtimeClass.asInstanceOf[Class[A]])
      case _   => injector.getInstance(key(m))
    }
  }

  private def key[A](implicit a: Manifest[A]): Key[A] = {
    val targs = a.typeArguments.map(_.runtimeClass)
    Key.get(Types.newParameterizedType(a.runtimeClass, targs:_*)).asInstanceOf[Key[A]]
  }
}
