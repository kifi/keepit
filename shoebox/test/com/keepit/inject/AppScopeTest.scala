package com.keepit.inject

import com.google.inject.Guice
import com.google.inject.Inject
import com.google.inject.Injector
import com.google.inject.Key
import com.tzavellas.sse.guice.ScalaModule
import org.junit.runner.RunWith
import org.specs2.runner.JUnitRunner
import org.specs2.mutable.SpecificationWithJUnit
import play.api.Plugin
import play.api.test._

class TestPlugin extends Plugin {
  var started = false
  var stopped = false
  override def enabled = true
  override def onStart(): Unit = { assert(!started); started = true }
  override def onStop(): Unit = { assert(started && !stopped); stopped = true }
}

@AppScoped
class PluginA extends TestPlugin

@AppScoped
class PluginB extends TestPlugin

@AppScoped
class PluginC @Inject() (injector: Injector) extends TestPlugin {
  override def onStart(): Unit = {
    super.onStart()
    val pluginA = injector.inject[PluginA]
    val pluginB = injector.inject[PluginB]
    assert(pluginA.started)
    assert(pluginB.started)
  }
}

class TestModule extends ScalaModule {
  def configure(): Unit = {
    var appScope = new AppScope
    bindScope(classOf[AppScoped], appScope)
    bind[AppScope].toInstance(appScope)
  }
}

@RunWith(classOf[JUnitRunner])
class AppScopeTest extends SpecificationWithJUnit {

  "AppScope" should {
    "fail if entered or exited incorrectly" in {
      val app = FakeApplication()
      val scope = new AppScope

      scope.onStart(app) // ok

      scope.onStart(app) must throwA[Exception]

      scope.onStop(app) // ok

      scope.onStart(app) must throwA[Exception]
      scope.onStop(app) must throwA[Exception]
    }

    "start and stop injected plugins" in {
      val app = FakeApplication()
      val scope = new AppScope

      val providerA = scope.scope(Key.get(classOf[PluginA]), provide(new PluginA))
      val providerB = scope.scope(Key.get(classOf[PluginB]), provide(new PluginB))

      // providers refuse to provide instances before scope started
      providerA.get must throwA[Exception]
      providerB.get must throwA[Exception]

      scope.onStart(app)

      // providers return the same instance when called repeatedly
      val pluginA = providerA.get
      val pluginB = providerB.get
      providerA.get eq pluginA must_== true
      providerB.get eq pluginB must_== true

      // plugins started when scope started
      pluginA.started must_== true
      pluginA.stopped must_== false
      pluginB.started must_== true
      pluginB.stopped must_== false

      scope.onStop(app)

      // plugins stopped when scope stopped
      pluginA.stopped must_== true
      pluginB.stopped must_== true
    }

    "work with guice" in {
      val app = FakeApplication()
      val injector = Guice.createInjector(new TestModule())
      val scope = injector.inject[AppScope]

      scope.onStart(app)

      // providers return the same instance when called repeatedly
      val pluginA = injector.inject[PluginA]
      val pluginB = injector.inject[PluginB]
      injector.inject[PluginA] eq pluginA must_== true
      injector.inject[PluginB] eq pluginB must_== true

      // plugins started when scope started
      pluginA.started must_== true
      pluginA.stopped must_== false
      pluginB.started must_== true
      pluginB.stopped must_== false

      scope.onStop(app)

      // plugins stopped when scope stopped
      pluginA.stopped must_== true
      pluginB.stopped must_== true
    }

    "allow injecting plugins that depend on other plugins" in {
      val app = FakeApplication()

      val injector = Guice.createInjector(new TestModule())
      val scope = injector.inject[AppScope]

      scope.onStart(app)

      val pluginC = injector.inject[PluginC]
      pluginC.started must_== true
    }
  }
}
