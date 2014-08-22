import com.typesafe.sbt.SbtScalariform._
import com.typesafe.sbteclipse.core.EclipsePlugin.EclipseKeys
import play.Project._
import sbt.Keys._
import sbt._

import scalariform.formatter.preferences._


object Global {

  val _scalacOptions = Seq("-unchecked", "-deprecation", "-feature", "-language:reflectiveCalls",
    "-language:implicitConversions", "-language:postfixOps", "-language:dynamics","-language:higherKinds",
    "-language:existentials", "-language:experimental.macros", "-Xmax-classfile-name", "140")

  val _javaTestOptions = Seq("-Xms512m", "-Xmx2g", "-XX:PermSize=256m", "-XX:MaxPermSize=512m")

  val _testOptions = Seq(
    Tests.Argument("sequential", "true"),
    Tests.Argument("threadsNb", "16"),
    Tests.Argument("showtimes", "true"),
    Tests.Argument("stopOnFail", "true"),
    Tests.Argument("failtrace", "true")
  )

  val macroParadiseSettings = Seq(
    resolvers += Resolver.sonatypeRepo("releases"),
    addCompilerPlugin("org.scalamacros" % "paradise" % "2.0.1" cross CrossVersion.full)
  )

  val _commonResolvers = Seq(
    Resolver.url("sbt-plugin-snapshots",
      new URL("http://repo.42go.com:4242/fortytwo/content/groups/public/"))(Resolver.ivyStylePatterns),
    // new URL("http://repo.scala-sbt.org/scalasbt/sbt-plugin-snapshots/"))(Resolver.ivyStylePatterns),
    "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/",
    // "kevoree Repository" at "http://maven2.kevoree.org/release/",
    "FortyTwo Public Repository" at "http://repo.42go.com:4242/fortytwo/content/groups/public/",
    "FortyTwo Towel Repository" at "http://repo.42go.com:4242/fortytwo/content/repositories/towel"
    //for org.mongodb#casb
    // "snapshots" at "https://oss.sonatype.org/content/repositories/snapshots",
    // "releases"  at "https://oss.sonatype.org/content/groups/scala-tools",
    // "terracotta" at "http://www.terracotta.org/download/reflector/releases/",
    // "The Buzz Media Maven Repository" at "http://maven.thebuzzmedia.com"
  )

  val settings = scalariformSettings ++ macroParadiseSettings ++ Seq(
    //updateOptions := updateOptions.value.withConsolidatedResolution(true),
    scalacOptions ++= _scalacOptions,
    resolvers ++= _commonResolvers,
    javaOptions in Test ++= _javaTestOptions,
    testOptions in Test ++= _testOptions,
    parallelExecution in Test := false,
    EclipseKeys.skipParents in ThisBuild := false,
    sources in doc in Compile := List(),
    Keys.fork := false,
    aggregate in update := false,
    ScalariformKeys.preferences := ScalariformKeys.preferences.value
      .setPreference(DoubleIndentClassDeclaration, true)
  )

}

object PlayGlobal {
  val _routesImport = Seq(
    "com.keepit.common.db.{ExternalId, Id, State, SequenceNumber}",
    "com.keepit.model._",
    "com.keepit.social._",
    "com.keepit.search._",
    "com.keepit.cortex.core._",
    "com.keepit.cortex.models.lda._",
    "com.keepit.common.mail.EmailAddress",
    "com.keepit.common.crypto._",
    "org.joda.time.DateTime",
    "com.keepit.common.time._"
  )

  val _templatesImport = Seq(
    "com.keepit.common.db.{ExternalId, Id, State}",
    "com.keepit.model._",
    "com.keepit.social._",
    "com.keepit.search._"
  )

  val settings = Seq(
    routesImport ++= _routesImport,
    templatesImport ++= _templatesImport
  )
}
