package com.keepit.inject

import com.google.inject._

import play.api.Mode._
import play.api.Mode
import net.codingwell.scalaguice.InjectorExtensions.ScalaInjector
import java.util.concurrent.atomic.AtomicBoolean
import com.google.inject.util.Modules
import com.keepit.FortyTwoGlobal

sealed trait InjectorProvider {

  def mode: Mode
  def modules: Seq[Module]
  implicit def injector: Injector

  implicit def richInjector(injector: Injector): ScalaInjector = new ScalaInjector(injector)

  protected def createInjector(modules: Module*) = mode match {
    case Mode.Dev => Guice.createInjector(Stage.DEVELOPMENT, modules: _*)
    case Mode.Prod => Guice.createInjector(Stage.PRODUCTION, modules: _*)
    case Mode.Test => Guice.createInjector(Stage.DEVELOPMENT, modules: _*)
    case m => throw new IllegalStateException(s"Unknown mode $m")
  }

  def withCustomInjector[T](overridingModules: Module*)(f: Injector => T) = {
    val customModules = Modules.`override`(modules: _*).`with`(overridingModules:_*)
    val injector = createInjector(customModules)

    f(injector)
  }

  def inject[A](implicit m: Manifest[A], injector: Injector): A = injector.instance[A]
  def provide[T](func: => T): Provider[T] = new Provider[T] { def get = func }

}

trait EmptyInjector extends InjectorProvider {

  private val creatingInjector = new AtomicBoolean(false)
  private val _initialized = new AtomicBoolean(false)
  def initialized = _initialized.get

  /**
   * While executing the code block that return the injector at Application start,
   * we found few times that one of the injected components was using inject[Foo] during their construction
   * instead using the constructor injector (a bug).
   * In that case the application will try to access the injector - that is being created at this moment.
   * Then scala executes the injector code block again which eventually creates an infinit stack trace and out of stack space.
   * The exception is to help us understand the problem.
   * As we kill the inject[Foo] pattern then there will be no use for the creatingInjector.
   * We'll still want the lazy val since the injector may be depending on things from the application (like the configuration info)
   * and we don't want to instantiate it until the onStart(app: Application) is executed.
   */

  implicit lazy val injector: Injector = {
    if (creatingInjector.getAndSet(true)) throw new Exception("Injector is being created!")
    val injector = createInjector(modules:_*)
    _initialized.set(true)
    injector
  }
}

trait ApplicationInjector extends InjectorProvider {

  import play.api.Play.current

  private def fortyTwoGlobal = current.global.asInstanceOf[FortyTwoGlobal]
  def modules = fortyTwoGlobal.modules
  def mode = fortyTwoGlobal.mode
  implicit def injector = fortyTwoGlobal.injector
}
