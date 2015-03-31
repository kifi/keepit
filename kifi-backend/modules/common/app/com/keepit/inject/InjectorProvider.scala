package com.keepit.inject

import com.google.inject.{ Injector, Guice, Stage, Module, Key, Provider }

import play.api.Mode._
import play.api.Mode
import net.codingwell.scalaguice.InjectorExtensions._
import java.util.concurrent.atomic.AtomicBoolean
import com.google.inject.util.Modules
import com.keepit.FortyTwoGlobal
import net.codingwell.scalaguice._

trait InjectorProvider {

  def mode: Mode
  def module: Module
  def injector: Injector

  //implicit def richInjector(injector: Injector): ScalaInjector = new ScalaInjector(injector)

  protected def createInjector(modules: Module*) = {
    val modeModule = new ScalaModule {
      def configure(): Unit = bind[Mode].toInstance(mode)
    }
    val modulesWithMode = modules :+ modeModule
    val stage = mode match {
      case Mode.Dev => Stage.DEVELOPMENT
      case Mode.Prod => Stage.PRODUCTION
      case Mode.Test => Stage.DEVELOPMENT
      case m => throw new IllegalStateException(s"Unknown mode $m")
    }
    Guice.createInjector(stage, modulesWithMode: _*)
  }

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

  lazy val injector: Injector = {
    if (creatingInjector.getAndSet(true)) throw new Exception("Injector is being created!")
    val injector = createInjector(module)
    _initialized.set(true)
    injector
  }
}

trait ApplicationInjector extends InjectorProvider {

  import play.api.Play.current

  private def fortyTwoGlobal = current.global.asInstanceOf[FortyTwoGlobal]
  def module = fortyTwoGlobal.module
  def mode = fortyTwoGlobal.mode
  implicit def injector = fortyTwoGlobal.injector
}
